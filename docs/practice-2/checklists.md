# Чек-листы

## 1. Дымовое тестирование

Быстрая оценка работоспособности системы после сборки. Выполняется на каждом развёрнутом стенде
до запуска глубоких проверок.

**Инфраструктура**

- [ ] `.env` создан из `.env.example`, порты `3000`, `3001`, `8080–8097`, `9090`, `15672` свободны
- [ ] `docker compose up -d` поднимает 11 сервисов, PostgreSQL и RabbitMQ без ошибок
- [ ] `docker compose ps` — все контейнеры в состоянии `Up (healthy)`
- [ ] PostgreSQL доступен, миграции применены 
- [ ] RabbitMQ Management UI доступен, очереди `transaction-log` и `card-notifications` созданы

**Health-check сервисов**

- [ ] Gateway: `GET :8080/health` → 200
- [ ] Card Management: `GET :8081/health` → 200
- [ ] Switch: `GET :8082/health` → 200
- [ ] Authorization: `GET :8083/health` → 200
- [ ] Merchant Simulator: `GET :8084/health` → 200
- [ ] Terminal Simulator: `GET :8085/health` → 200
- [ ] Transaction Logger: `GET :8088/health` → 200
- [ ] Bin Lookup: `GET :8096/actuator/health` → 200
- [ ] Notification Service: `GET :8097/actuator/health` → 200
- [ ] Web Dashboard: `http://localhost:3000` открывается и загружает статистику

**Ключевые операции**

- [ ] `scripts/smoke-test.sh` завершается `🎉 ALL CHECKS PASSED`
- [ ] Генерация карт `POST /api/cards/generate` создаёт не менее 20 штук
- [ ] У созданных карт PAN из 16 цифр и проходит проверку Луна, `expiryDate` = MMYY месяца +3 года,
  `status = ACTIVE`, `monthlyLimit = dailyLimit × 30`
- [ ] Одна авторизация по валидной карте с результатом `APPROVED`, `responseCode = 00`, `availableBalance` уменьшен
- [ ] Один сценарий с `DECLINED`, корректным `responseCode` и `declineReason`
- [ ] Транзакция видна в `GET /api/transactions/search` и в Web Dashboard
- [ ] WebSocket `ws://localhost:8088/ws/transactions` отдаёт событие по новой транзакции
- [ ] `POST /api/simulator/terminal/run` (сценарий `normal`) возвращает статистику `submitted/approved/declined`

## 2. Критический путь

Проверка ключевых пользовательских сценариев процессинговой цепочки.

**Сквозной сценарий (happy path)**

- [ ] `POST /api/simulator/terminal/run` со сценарием `normal` создаёт транзакции и возвращает статистику
- [ ] Каждая транзакция проходит всю цепочку (Terminal Simulator → Gateway → Switch → Authorization →
  Card Management → Switch → Transaction Logger)
- [ ] Транзакция находится в Transaction Logger по `rrn`, статус `APPROVED`, `responseCode = 00`,
  `rrn` уникален (12 цифр), `authCode` - 6 символов
- [ ] `availableBalance` карты уменьшился ровно на сумму одобренных транзакций
- [ ] `limit_usage` содержит корректные `daily_amount` и `monthly_amount` за текущий день и месяц
- [ ] Транзакция отображается в Dashboard: KPI-карточки, график потока, диаграмма approved/declined, таблица
- [ ] `POST /api/simulator/merchant/run` проходит для сценариев `grocery`, `electronics`,
  `restaurant`, `travel`; MCC, мерчант и валюта соответствуют сценарию
- [ ] WebSocket `ws://localhost:8088/ws/transactions` доставляет событие без перезагрузки страницы

**Decline-пути (по одному представителю на код)**

- [ ] Карта не найдена → `14`, `CARD_NOT_FOUND`
- [ ] Карта `INACTIVE` → `05`, `CARD_INACTIVE`
- [ ] Карта `BLOCKED` → `05`, `CARD_BLOCKED`
- [ ] Истёкший срок действия (MMYY прошлого месяца) → `54`, `CARD_EXPIRED`
- [ ] Превышение дневного лимита → `61`, `EXCEEDS_AMOUNT_LIMIT` (сценарий `declines_test`)
- [ ] Превышение месячного лимита → `61`, `EXCEEDS_AMOUNT_LIMIT`
- [ ] Недостаточно средств → `51`, `INSUFFICIENT_FUNDS`
- [ ] Card Management недоступен → `96`, `SERVICE_UNAVAILABLE` (после возврата сервиса система
  работает без перезапуска)
- [ ] Отклонённая транзакция не изменяет ни `availableBalance`, ни `limit_usage`
- [ ] Каждый decline-код виден в `GET /api/transactions/search` по фильтру `declineReason`

**Отказоустойчивость и целостность данных**

- [ ] При недоступности RabbitMQ Switch использует синхронный fallback `POST /api/internal/log`
  либо выполняет reversal - потери транзакции не происходит
- [ ] Reversal по `rrn` возвращает средства и снимает запись из `limit_usage`
- [ ] Событие проходит `PENDING` → `PROCESSED`, Notification Service
  получает сообщение из очереди `card-notifications`
- [ ] При остановленном RabbitMQ события переходят в `FAILED` после 3 ретраев с backoff
- [ ] Дубли `rrn` отсутствуют при параллельной отправке транзакций
- [ ] Rate-limit Gateway (100 задач/с на IP) отдаёт `429` при превышении, уже принятые транзакции не теряются
- [ ] Повторный запуск `docker compose up -d` на существующей БД не портит данные (карты и транзакции на месте)

**Поиск, пагинация, отображение**

- [ ] `GET /api/cards?limit=10&offset=0&status=ACTIVE&bin=400000` возвращает согласованные `total` и `cards`
- [ ] Пагинация карт и транзакций не теряет и не дублирует записи
- [ ] Карты со статусом `DELETED` не возвращаются ни в `GET /api/cards`, ни в `GET /api/cards/{pan}`
- [ ] Данные Dashboard совпадают с `GET /api/dashboard/stats` и результатами поиска в Logger
