# Практика 3 — Тест-кейсы: новые, распределение старых, пропорции

## Правило выбора уровня

Для тестового сценария подбирается минимально возможный уровень тестирования 

| Уровень         | Что проверяет                                            | Когда выбираем                                                     |
|-----------------|----------------------------------------------------------|--------------------------------------------------------------------|
| **unit**        | чистую логику в одном классе (без HTTP и БД)             | ветвления статуса/expiry/баланса в Java, валидаторы, Luhn и тп     |
| **api**         | контракт REST (запрос → ответ, статус, тело, reason)     | есть фиксированный HTTP-контракт                                   |
| **integration** | связь с БД/очередью (изменение состояния, доставка, DLQ) | проверяется персистентность, нативный SQL или асинхронная доставка |
| **e2e**         | сквозной сценарий через весь контур                      | критичный бизнес-путь, требующий всех сервисов                     |

**Примечание:** фактическое сравнение дневного и месячного лимитов выполняется в нативном SQL [`upsertLimitUsage`](../../services/authorization/src/main/java/com/processing/authorization/repositories/LimitUsageRepository.java#L62), а не в Java. Проверка реального решения SQL - integration. Unit-тест с замоканным репозиторием может проверять только ветку, что репозиторий вернул 0 → DECLINED 61, но не корректность самого SQL.

---

## (б) Новые тест-кейсы

Всего новых кейсов: **24** (unit — 11, api — 4, integration — 6, e2e — 3). Покрыты sync (валидация, лимиты/баланс, резервирование) и async (доставка в Logger, publisher-confirm/reversal, outbox → Notification, DLQ). N-INT-04 и N-INT-06 фиксируют ожидаемое поведение требований; текущая реализация содержит известные расхождения, поэтому эти проверки могут выявить дефекты.

### Unit — Authorization Service
| ID | Связанное требование                                      | Уровень пирамиды | Вид          | Предусловие                                                                                                                                                                                                     | Шаги | Ожидаемый результат                                                                 | Источник                                                                                                                                                    |
|---|-----------------------------------------------------------|------------------|--------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---|-------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------|
| N-UNIT-01 | Авторизация одобряется при ACTIVE и достаточных средствах | unit             | Позитивный   | [`AuthServiceImpl`](../../services/authorization/src/main/java/com/processing/authorization/services/AuthServiceImpl.java#L43) с моками: `getCard`→ACTIVE, `balance`≥amount, `upsertLimitUsage`=1, `reserve`=OK | Вызвать `authorize(request, now)` | `APPROVED`, code `00`, `rrn`/`authCode` не пусты                                    | Класс: `card_status=ACTIVE` + достаточный баланс                                                                                                            |
| N-UNIT-02 | Карта BLOCKED отклоняется                                 | unit             | Негативный   | `getCard`→BLOCKED (или CMS отдал `402`)                                                                                                                                                                         | Вызвать `authorize()` | `DECLINED`, code `05`, reason `CARD_BLOCKED`; `reserve` не вызывался                | Класс: `card_status=BLOCKED`                                                                                                                                |
| N-UNIT-03 | Баланс ровно на 1 больше суммы                            | unit             | Негативный   | `balance=100000`, `amount=100001`                                                                                                                                                                               | Вызвать `authorize()` | `DECLINED`, code `51`, reason `INSUFFICIENT_FUNDS`; `upsertLimitUsage` не вызывался | Граница: `amount=availableBalance+1` (OFF)                                                                                                                  |
| N-UNIT-04 | Сервис обрабатывает отказ репозитория по лимиту           | unit             | Негативный   | `upsertLimitUsage` замокан и возвращает `0`                                                                                                                                                                     | Вызвать `authorize()` | `DECLINED`, code `61`, reason `EXCEEDS_AMOUNT_LIMIT`; тест не проверяет SQL-условие | Ветвление сервиса при результате репозитория `0`                                                                                                            |
| N-UNIT-05 | Отказ внешнего BIN Lookup не ломает авторизацию           | unit             | Негативный   | `binLookupClient.getIssuerId` → `Optional.empty`/ошибка                                                                                                                                                         | Вызвать `authorize()` | Авторизация продолжается → `APPROVED`                                               | Контракт: graceful degradation ([строки 65-70](../../services/authorization/src/main/java/com/processing/authorization/services/AuthServiceImpl.java#L65))  |
| N-UNIT-06 | Недоступность CMS даёт 96                                 | unit             | Негативный   | `getCard` бросает `ServiceUnavailableException`                                                                                                                                                                 | Вызвать `authorize()` | `DECLINED`, code `96`, reason `SERVICE_UNAVAILABLE`                                 | Сценарий: отказ зависимости ([DeclineOutcome](../../services/authorization/src/main/java/com/processing/authorization/constants/DeclineOutcome.java#L61))   |

### Unit — Card-Management (outbox, генерация, сущности)
| ID | Связанное требование | Уровень пирамиды | Вид | Предусловие | Шаги | Ожидаемый результат | Источник |
|---|---|---|---|---|---|---|---|
| N-UNIT-07 | Успешная публикация события из outbox | unit | Позитивный | [`OutboxEventProcessorImpl`](../../services/card-management/src/main/java/com/processing/cardmanagement/services/OutboxEventProcessorImpl.java#L32), mock `RabbitTemplate` без ошибок | `processSingleEvent(event)` | Статус события → `processed`, `save()` вызван | Контракт компонента outbox |
| N-UNIT-08 | Outbox уходит в `failed` после исчерпания ретраев | unit | Негативный | mock `RabbitTemplate` бросает исключение; `maxRetryCount=3` ([application.properties](../../services/card-management/src/main/resources/application.properties#L67)); репозиторий возвращает сохранённую версию события | Выполнить попытку; перечитать обновлённое событие из репозитория; повторить до 3-й попытки | `retryCount` растёт между сохранёнными версиями; на 3-й попытке статус → `failed` | Граница: `retryCount = maxRetryCount` |
| N-UNIT-09 | Генерация PAN корректна по Луну | unit | Позитивный | `PanGenerator` для `bin=400000` | `generatePan(bin)` | 16 цифр, PAN проходит [`LuhnValidator`](../../services/card-management/src/main/java/com/processing/cardmanagement/services/LuhnValidator.java) | Класс: `pan_format=valid_luhn` |
| N-UNIT-10 | Повторный откат резерва запрещён | unit | Негативный | `Reservation` в статусе `ROLLED_BACK` | `reservation.startRollback(amount)` | Выброшен `RollbackAlreadySatisfiedException`; новый `ReservationRollback` не создан | Состояние: `ReservationStatus=ROLLED_BACK` ([Reservation.java:49](../../services/card-management/src/main/java/com/processing/cardmanagement/models/Reservation.java#L49)) |
| N-UNIT-11 | Валидация BIN аннотацией `@Bin` | unit | Негативный | `BinValidator` создан без Spring-контекста | Проверить `"40000"`, `"4000000"`, `"4000A0"`, `"400000"` | 5/7 символов и нецифровой символ — невалидны; `"400000"` — валиден | Граница: длина bin 5/6/7 |

### API — синхронные контракты
| ID | Связанное требование | Уровень пирамиды | Вид | Предусловие | Шаги | Ожидаемый результат | Источник |
|---|---|---|---|---|---|---|---|
| N-API-01 | Gateway маршрутизирует транзакцию в Switch | api | Позитивный | подняты gateway + switch; Authorization — WireMock (APPROVED 00) | `POST /api/transactions` с валидным телом (bin=400000) | Запрос переписан на `/api/internal/route`, `200`, тело с `responseCode`; заглушка получила `issuerId=ISS001` | Контракт: `RewritePath` ([application.yml:73](../../services/gateway/src/main/resources/application.yml#L73)) |
| N-API-02 | Валидация формата BIN при создании карты | api | Негативный | CMS поднят | `POST /api/cards` с `bin=40000` (5 цифр) | `400`, `ErrorResponse` с ошибкой валидации BIN; валидатор требует ровно 6 цифр | Граница: длина BIN = 5 вместо 6 (`@Bin`, `BinValidator`) |
| N-API-03 | Контракт Authorization↔CMS: 402 → отказ 05 | api | Негативный | CMS-заглушка возвращает `402` | `POST /api/internal/authorize` | `DECLINED`, code `05`, reason `CARD_BLOCKED` | Контракт: маппинг `402`→`PaymentRequiredException` ([CardManagementClientImpl:52](../../services/authorization/src/main/java/com/processing/authorization/client/CardManagementClientImpl.java#L52)) |
| N-API-04 | Контракт Authorization↔CMS: 404 → 14 | api | Негативный | CMS-заглушка возвращает `404` | `POST /api/internal/authorize` | `DECLINED`, code `14`, reason `CARD_NOT_FOUND` | Контракт: `onStatus(404)` ([CardManagementClientImpl:55](../../services/authorization/src/main/java/com/processing/authorization/client/CardManagementClientImpl.java#L55)) |

### Integration — БД, SQL-лимиты и асинхронные связи
| ID | Связанное требование | Уровень пирамиды | Вид | Предусловие | Шаги | Ожидаемый результат | Источник |
|---|---|---|---|---|---|---|---|
| N-INT-01 | Транзакция доходит до Logger (eventual consistency) | integration | Позитивный | RabbitMQ + PostgreSQL + switch + logger подняты | Провести транзакцию через Switch | После ответа или параллельно с ним в БД Logger eventually появляется запись с тем же `stan`; точный порядок не гарантирован, проверять polling | Сценарий: async-доставка ([TransactionLogListener](../../services/transaction-logger/src/main/java/com/processing/transactionlogger/listener/TransactionLogListener.java#L27)) |
| N-INT-02 | Ошибка публикации → откат, код 96 | integration | Негативный | брокер недоступен (`AmqpException`) | Провести транзакцию через Switch | Switch вызывает rollback, клиент получает `DECLINED` `96`, резерв `ROLLED_BACK`, баланс восстановлен | Сценарий: reversal ([RouteService:87](../../services/switch/src/main/java/com/processing/service/RouteService.java#L87)) |
| N-INT-03 | outbox → RabbitMQ → Notification | integration | Позитивный | CMS + PostgreSQL + RabbitMQ + notification | Создать карту (`POST /api/cards`) | outbox `PENDING`→`PROCESSED`; сообщение `card.*` доставлено в `card-notifications`, запись в `notifications` | Сценарий: outbox → consumer ([OutboxEventProcessorImpl:35](../../services/card-management/src/main/java/com/processing/cardmanagement/services/OutboxEventProcessorImpl.java#L35)) |
| N-INT-04 | После 3 неудачных доставок сообщение уходит в DLQ | integration | Негативный / проверка требования | RabbitMQ 3.13 поднят; consumer стабильно падает при обработке; используется очередь, объявленная текущей конфигурацией приложения | Опубликовать сообщение и дождаться 3 неудачных доставок; проверить `transaction-log-dlq` | После 3 неудачных доставок сообщение оказывается в `transaction-log-dlq`; TTL DLQ — 60 с. **Известный риск реализации:** сейчас очередь объявлена как durable classic queue с аргументом `x-max-delivery=3`, действие которого в этой конфигурации не подтверждено; тест может выявить дефект | Граница: 3 попытки + состояние очереди; `RabbitMQConfig` |
| N-INT-05 | Граница дневного лимита проверяется в SQL | integration | Позитивный | Authorization + реальный PostgreSQL (Testcontainers); клиенты моканы; карта ACTIVE: balance 2000.00, dailyLimit 15000.00, monthlyLimit 300000.00; в `limit_usage` на дату `daily_amount=14000.00` | 1. `authorize(sum=1000.00)`; 2. прочитать `limit_usage`; 3. `authorize(sum=0.01)` | Шаг 1: `APPROVED` `00`; шаг 2: `daily_amount=15000.00`; шаг 3: `DECLINED` `61` | Граница `dailyLimit` ON/OFF + состояние БД ([upsertLimitUsage](../../services/authorization/src/main/java/com/processing/authorization/repositories/LimitUsageRepository.java#L62)) |
| N-INT-06 | Нет publisher-confirm за 2 секунды → rollback и DECLINED 96 | integration | Негативный / проверка требования | Switch + Authorization + CMS + RabbitMQ; брокер принимает публикацию, но подтверждение не поступает в заданный интервал | Выполнить платёж; симулировать отсутствие publisher-confirm; дождаться результата | По требованию система должна выполнить rollback резерва, вернуть `DECLINED` code `96`, а резерв должен стать `ROLLED_BACK`. **Ожидаемый дефект текущего кода:** `LoggerClient` не ожидает confirm, поэтому таймаут подтверждения сейчас не реализован и этот сценарий не будет обеспечен как описано | Сценарий: publisher-confirm + reversal; `LoggerClient`, `RouteService`, конфигурация RabbitMQ |

### E2E — сквозные сценарии
| ID | Связанное требование | Уровень пирамиды | Вид | Предусловие | Шаги | Ожидаемый результат | Источник |
|---|---|---|---|---|---|---|---|
| N-E2E-01 | Сквозной happy path | e2e | Позитивный | весь стенд поднят; карта ACTIVE (bin 400000, balance 1000.00, лимиты достаточны) | Терминал → Gateway → Switch → Auth → CMS; затем дождаться через polling записи в Logger и прочитать `GET /api/cards/{pan}` | `APPROVED` `00`; в Logger запись с тем же `stan`/`rrn`; баланс уменьшен; `limit_usage` обновлён | Сценарий: сквозной (A4, сценарий 1) |
| N-E2E-02 | Сквозной declined по блокировке | e2e | Негативный | карта переведена в BLOCKED (`PATCH /api/cards/{pan}`) | Терминал → … → Logger | `DECLINED` `05`; после eventual consistency в Logger появляется запись DECLINED с `CARD_BLOCKED`; баланс не изменился | Сценарий + состояние (A6) |
| N-E2E-03 | Сквозной expired | e2e | Негативный | карта с истёкшим `expiryDate` | Терминал → … → Logger | `DECLINED` `54`, reason `CARD_EXPIRED` | Состояние: EXPIRED (A6) |

---

## (в) Распределение тест-кейсов практики 2

Источник: [`docs/practice-2/test-design.md`](../practice-2/test-design.md). Для каждого кейса указано, **что он проверяет**, и уровень пирамиды с обоснованием.

### Card Management — классы эквивалентности
| ID (пр. 2) | Название | Что проверяет тест | Уровень | Обоснование |
|---|---|---|---|---|
| CM-CE-001 | Создание карты | REST-контракт `POST /api/cards` (201, ACTIVE, PAN) | api | проверяется контракт эндпоинта; алгоритм Луна — отдельно в unit |
| CM-CE-002 | Чтение карты | контракт `GET /api/cards/{pan}` | api | запрос → ответ |
| CM-CE-003 | Обновление карты | контракт `PATCH` | api | запрос → ответ |
| CM-CE-004 | Удаление карты | `DELETE` → 204, статус DELETED | api | контракт эндпоинта |
| CM-CE-005 | Резервирование средств | уменьшение баланса в БД | integration | проверяется персистентное состояние (JDBC) |
| CM-CE-006 | Генерация карт | 100 карт, распределение 95/3/2 в БД | integration | проверяется персистентный набор карт |
| CM-CE-007 | Карта ACTIVE | чтение статуса | api | контракт чтения |
| CM-CE-008 | Карта INACTIVE | чтение статуса | api | контракт чтения |
| CM-CE-009 | Карта BLOCKED | чтение статуса | api | контракт чтения |
| CM-CE-010 | Карта EXPIRED | чтение статуса | api | контракт чтения |
| CM-CE-011 | Валидный PAN по Luhn | алгоритм Луна | unit | чистая функция [`LuhnValidator`](../../services/card-management/src/main/java/com/processing/cardmanagement/services/LuhnValidator.java) |
| CM-CE-012 | Невалидный PAN по Luhn | отклонение по Луну | unit | чистая логика валидации |
| CM-CE-013 | Валидный срок действия | `POST` при future expiry | api | контракт создания |
| CM-CE-014 | Просроченный срок при create | `400` | api | контракт валидации |
| CM-CE-015 | Валидный BIN | `POST` bin=400000 | api | контракт создания |
| CM-CE-016 | Невалидный BIN | `400` | api | контракт валидации |

### Card Management — граничные значения
| ID (пр. 2) | Название | Что проверяет тест | Уровень | Обоснование |
|---|---|---|---|---|
| CM-BV-001 | amount=balance (ON) | баланс=0 в БД | integration | проверяется изменение состояния в БД |
| CM-BV-002 | amount=balance+1 (OFF) | `400` INSUFFICIENT_FUNDS | api | контракт ошибки эндпоинта |
| CM-BV-003 | amount=0 (OFF) | `400` INVALID_AMOUNT | api | контракт валидации |
| CM-BV-004 | amount=1 (ON) | `200`, баланс 99999 | integration | проверяется состояние баланса в БД |

### Card Management — pairwise
| ID (пр. 2) | Название | Что проверяет тест | Уровень | Обоснование |
|---|---|---|---|---|
| CM-PW-001 | Удаление активной с невалидным PAN | 404 | api | контракт |
| CM-PW-002 | Создание blocked+expired | 400 EXPIRED_DATE | api | контракт валидации |
| CM-PW-003 | Создание expired+invalid BIN | 400 INVALID_BIN | api | контракт валидации |
| CM-PW-004 | Удаление blocked с невалидным PAN | 404 | api | контракт |
| CM-PW-005 | Генерация inactive с невалидным BIN | 400 INVALID_BIN | api | контракт |
| CM-PW-006 | Создание active с expired | 400 EXPIRED_DATE | api | контракт |
| CM-PW-007 | Генерация blocked с валидным BIN | ~2% BLOCKED в БД | integration | проверяется распределение в persisted-наборе |
| CM-PW-008 | Резерв 0 на карте expired | 400 INVALID_AMOUNT | api | контракт |
| CM-PW-009 | Чтение active с валидным PAN | 200 | api | контракт |
| CM-PW-010 | Обновление inactive с валидным PAN | 200 | api | контракт |
| CM-PW-011 | Чтение inactive с невалидным PAN | 404 | api | контракт |
| CM-PW-012 | Удаление expired с валидным PAN | 200 DELETED | api | контракт |
| CM-PW-013 | Обновление expired с невалидным PAN | 404 | api | контракт |
| CM-PW-014 | Удаление inactive с валидным PAN | 200 DELETED | api | контракт |
| CM-PW-015 | Генерация expired с валидным BIN | генератор создаёт future expiry | api | контракт генератора |
| CM-PW-016 | Генерация active с expired | future expiry | api | контракт генератора |
| CM-PW-017 | Создание inactive с валидным PAN | 400 INVALID_STATUS | api | контракт валидации |
| CM-PW-018 | Чтение blocked с валидным PAN | 200 BLOCKED | api | контракт |
| CM-PW-019 | Резерв сверх баланса на expired | 400 INSUFFICIENT_FUNDS | api | контракт ошибки |
| CM-PW-020 | Чтение expired с валидным PAN | 200 EXPIRED | api | контракт |
| CM-PW-021 | Резерв 0 на активной | 400 INVALID_AMOUNT | api | контракт |
| CM-PW-022 | Обновление blocked с невалидным PAN | 404 | api | контракт |
| CM-PW-023 | Резерв в пределах баланса на expired | 200, баланс 50000 | integration | проверяется состояние баланса в БД |
| CM-PW-024 | Резерв сверх баланса с невалидным PAN | 404 | api | контракт |
| CM-PW-025 | Обновление active с expired | 200 | api | контракт |

### Authorization — классы эквивалентности
| ID (пр. 2) | Название | Что проверяет тест | Уровень | Обоснование |
|---|---|---|---|---|
| AUTH-CE-001 | Неактивная карта | решение INACTIVE → 05 | unit | чистое ветвление в [`AuthServiceImpl`](../../services/authorization/src/main/java/com/processing/authorization/services/AuthServiceImpl.java#L91) при моке клиентов |
| AUTH-CE-002 | Заблокированная карта | BLOCKED → 05 | unit | чистая логика решения |
| AUTH-CE-003 | Просроченная карта | EXPIRED → 54 | unit | чистая логика решения |
| AUTH-CE-004 | Активная (базовый) | ACTIVE → 00 | unit | чистая логика решения |
| AUTH-CE-005 | POS-терминал | terminal=pos → 00 | unit | terminal не меняет решение → unit |
| AUTH-CE-006 | ATM-терминал | terminal=atm → 00 | unit | чистая логика |
| AUTH-CE-007 | ECOM-терминал | terminal=ecom → 00 | unit | чистая логика |
| AUTH-CE-008 | MCC grocery | mcc=grocery → 00 | unit | чистая логика |
| AUTH-CE-009 | MCC restaurant | mcc=restaurant → 00 | unit | чистая логика |
| AUTH-CE-010 | MCC electronics | mcc=electronics → 00 | unit | чистая логика |
| AUTH-CE-011 | MCC travel | mcc=travel → 00 | unit | чистая логика |

### Authorization — граничные значения
| ID (пр. 2) | Название | Что проверяет тест | Уровень | Обоснование |
|---|---|---|---|---|
| AUTH-BV-001 | amount=dailyLimit ON | 00 | integration | граница дневного лимита вычисляется в SQL `upsertLimitUsage` |
| AUTH-BV-002 | amount=dailyLimit+1 OFF | 61 | integration | решение зависит от SQL-условия лимита |
| AUTH-BV-003 | amount=monthlyLimit ON | 00 | integration | граница месячного лимита в SQL |
| AUTH-BV-004 | amount=monthlyLimit+1 OFF | 61 | integration | решение в SQL |
| AUTH-BV-005 | amount=availableBalance ON | 00, баланс=0 | integration | проверяется изменение баланса в БД (reserve) |
| AUTH-BV-006 | amount=availableBalance+1 OFF | 51 | unit | сравнение `amount > availableBalance` в Java |
| AUTH-BV-007 | expiry=current_month ON | 00 | unit | сравнение дат в Java (`expiryDate.atEndOfMonth()`) |
| AUTH-BV-008 | expiry=прошлый MMYY OFF | 54 | unit | сравнение дат в Java |
| AUTH-BV-009 | dailyUsed+amount=dailyLimit ON | 00 | integration | накопитель лимита обновляется в SQL |
| AUTH-BV-010 | dailyUsed+amount=dailyLimit+1 OFF | 61 | integration | решение в SQL |
| AUTH-BV-011 | monthlyUsed+amount=monthlyLimit ON | 00 | integration | накопитель месячного лимита в SQL |
| AUTH-BV-012 | monthlyUsed+amount=monthlyLimit+1 OFF | 61 | integration | решение в SQL |

### Authorization — pairwise
| ID (пр. 2) | Название | Что проверяет тест | Уровень | Обоснование |
|---|---|---|---|---|
| AUTH-PW-001 | INACTIVE (pos, electronics) | 05 CARD_INACTIVE | unit | статус карты — чистая логика |
| AUTH-PW-002 | Превышение monthly и баланса | 61 (monthly раньше) | integration | код 61 — решение SQL-условия лимита |
| AUTH-PW-003 | EXPIRED (atm, grocery) | 54 | unit | логика срока в Java |
| AUTH-PW-004 | Превышение всех лимитов | 61 (daily первым) | integration | код 61 из SQL |
| AUTH-PW-005 | BLOCKED (atm, restaurant) | 05 | unit | чистая логика |
| AUTH-PW-006 | INACTIVE (ecom, grocery) | 05 | unit | чистая логика |
| AUTH-PW-007 | INACTIVE+expired | 05 (статус раньше expiry) | unit | порядок проверок в Java |
| AUTH-PW-008 | BLOCKED+expired | 05 | unit | порядок проверок |
| AUTH-PW-009 | BLOCKED+expired | 05 | unit | порядок проверок |
| AUTH-PW-010 | Равенство лимитам и балансу | 00 | integration | успех зависит от границы лимита в SQL |
| AUTH-PW-011 | Равенство всем лимитам | 00 | integration | граница лимита в SQL |
| AUTH-PW-012 | Превышение дневного | 61 | integration | код 61 из SQL |
| AUTH-PW-013 | EXPIRED (current_month) | 54 | unit | логика срока в Java |
| AUTH-PW-014 | Равенство daily, выше баланса | 51 | unit | сравнение баланса в Java (до лимитов) |
| AUTH-PW-015 | EXPIRED (valid expiry) | 54 | unit | логика срока |
| AUTH-PW-016 | expired, выше баланса | 54 (expiry раньше) | unit | порядок проверок в Java |
| AUTH-PW-017 | Превышение daily | 61 | integration | код 61 из SQL |
| AUTH-PW-018 | Превышение daily+monthly | 61 | integration | код 61 из SQL |
| AUTH-PW-019 | INACTIVE+expired | 05 | unit | порядок проверок |
| AUTH-PW-020 | expired, выше баланса | 54 | unit | логика срока |
| AUTH-PW-021 | Равенство monthly, выше баланса | 51 | unit | сравнение баланса в Java |
| AUTH-PW-022 | EXPIRED (current_month) | 54 | unit | логика срока |
| AUTH-PW-023 | Равенство monthly и балансу | 00 | integration | граница лимита в SQL |
| AUTH-PW-024 | Равенство всем лимитам | 00 | integration | граница лимита в SQL |
| AUTH-PW-025 | BLOCKED (valid) | 05 | unit | чистая логика |
| AUTH-PW-026 | Превышение daily | 61 | integration | код 61 из SQL |

---

## (г) Анализ пропорций

| Уровень           | Старые |  Новые |   Итого |  Доля |
|-------------------|-------:|-------:|--------:|------:|
| unit              |     32 |     11 |      43 | 36.4% |
| api / integration |     62 |     10 |      72 | 61.0% |
| e2e               |    0   |    3   |     3   | 2.5%  |
| **Итого**         | **94** | **24** | **118** |  100% |

Разбивка старых по уровням: unit — 32, api — 37, integration — 25. Разбивка новых: unit — 11, api — 4, integration — 6, e2e — 3.

### Почему пирамида именно такая

1. **Низкая доля unit (36.4%) из-за лимитов в SQL.** Сравнение дневного и месячного лимитов вынесено в нативный SQL [`upsertLimitUsage`](../../services/authorization/src/main/java/com/processing/authorization/repositories/LimitUsageRepository.java#L62). Поэтому 9 кейсов `AUTH-BV` и 10 кейсов `AUTH-PW`, где исход определяется лимитом (коды 00 на границе и 61 при превышении), нельзя проверить unit-тестом, с моком репозитория такой тест проверял бы сам мок. Unit оставлены только те ветвления, что действительно живут в Java: статус карты, срок действия, сравнение с балансом, порядок проверок, валидаторы.

2. **Высокая доля api/integration (61.0%)** — закономерна для этой системы:
   - CRUD-контракты Card-Management (`POST/GET/PATCH/DELETE /api/cards`, `generate`, `bulk-update`) — 37 api-кейсов;
   - лимиты и операции, меняющие состояние (reserve/rollback, распределение генератора), а также async-швы (Logger, Notification) — 25 старых + 6 новых integration-кейсов.
   Плотные контракты (Gateway→Switch→Authorization→CMS) и наличие очередей смещают распределение от «классической» пирамиды 70/20/10 в сторону api/integration — это нормальный профиль микросервисной системы, и подгонять его под ориентир не нужно.

3. **Малая доля e2e (2.5%)** — сквозных критичных путей немного: happy path, declined (blocked/expired). Откат при ошибке публикации проверяется на **integration** (N-INT-02), а требуемый timeout publisher-confirm выделен отдельно в N-INT-06: он выявляет расхождение реализации с заданием. Сквозной E2E для этого сценария был бы дороже и нестабильнее. Держим 3 e2e-кейса.

### Проверка на перекосы

- Риск ice-cream cone (перекос в E2E) отсутствует, тк доля e2e 2.5%.
- Дефицит контрактных проверок отсутствует, тк api/integration 61.0%, покрыты и синхронные и async-стыки.
- Единственный риск — это маленький unit-слой относительно api/integration. 

**Вывод:** полученная пропорция ~**36.4 / 61.0 / 2.5** отклоняется от ориентира 70/20/10 в сторону api/integration. 