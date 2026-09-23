# Test-design ядра: Authorization и Card-Management

## 1. Классы эквивалентности

### Authorization Service

| Поле          | Классы        | Ожидаемый результат                                 |
|---------------|---------------|-----------------------------------------------------|
| card_status   | ACTIVE        | Продолжить проверку лимитов и баланса               |
| card_status   | INACTIVE      | DECLINED, responseCode="05", reason="CARD_INACTIVE" |
| card_status   | BLOCKED       | DECLINED, responseCode="05", reason="CARD_BLOCKED"  |
| card_status   | EXPIRED       | DECLINED, responseCode="54", reason="EXPIRED_CARD"  |
| terminal_type | pos           | Продолжить проверку (физическая транзакция)         |
| terminal_type | atm           | Продолжить проверку (банкомат)                      |
| terminal_type | ecom          | Продолжить проверку (интернет)                      |
| mcc           | grocery       | Продолжить проверку                                 |
| mcc           | restaurant    | Продолжить проверку                                 |
| mcc           | electronics   | Продолжить проверку                                 |
| mcc           | travel        | Продолжить проверку                                 |

### Card Management Service

| Поле        | Классы         | Представитель                       | Ожидаемый результат                         |
|-------------|----------------|-------------------------------------|---------------------------------------------|
| operation   | create         | POST /api/cards                     | Карта создана, status=ACTIVE, PAN по Luhn   |
| operation   | read           | GET /api/cards/{pan}                | Карта возвращена (200) или 404              |
| operation   | update         | PATCH /api/cards/{pan}              | Карта обновлена (200)                       |
| operation   | delete         | DELETE /api/cards/{pan}             | Мягкое удаление, status=DELETED             |
| operation   | reserve        | POST /api/cards/{pan}/reserve       | Баланс уменьшен на amount                   |
| operation   | generate       | POST /api/cards/generate            | Карты сгенерированы по распределению 95/3/2 |
| card_status | ACTIVE         | ACTIVE                              | Карта участвует во всех операциях           |
| card_status | INACTIVE       | INACTIVE                            | Карта не участвует в reserve                |
| card_status | BLOCKED        | BLOCKED                             | Карта не участвует в reserve                |
| card_status | EXPIRED        | EXPIRED                             | Карта не участвует в reserve                |
| pan_format  | valid_luhn     | PAN с корректной контрольной цифрой | Успешная обработка                          |
| pan_format  | invalid_luhn   | PAN с неверной контрольной цифрой   | Ошибка валидации при create                 |
| expiry      | valid_future   | Дата через 3 года (MMYY)            | Успешная обработка                          |
| expiry      | expired        | Прошлая дата (MMYY)                 | Ошибка при create, DECLINED при authorize   |
| bin         | valid_400000   | "400000"                            | Корректная генерация PAN                    |
| bin         | invalid_999999 | "999999"                            | Ошибка генерации PAN                        |

---

## 2. Граничные значения

### Authorization Service

| Поле                   | Граница          | ON                                  | OFF                                     | Ожидаемый результат                                                                               |
|------------------------|------------------|-------------------------------------|-----------------------------------------|---------------------------------------------------------------------------------------------------|
| amount vs dailyLimit   | dailyLimit       | amount = dailyLimit                 | amount = dailyLimit + 1                 | ON: APPROVED, responseCode="00"; OFF: DECLINED, responseCode="61", reason="EXCEEDS_DAILY_LIMIT"   |
| amount vs monthlyLimit | monthlyLimit     | amount = monthlyLimit               | amount = monthlyLimit + 1               | ON: APPROVED, responseCode="00"; OFF: DECLINED, responseCode="61", reason="EXCEEDS_MONTHLY_LIMIT" |
| amount vs balance      | availableBalance | amount = availableBalance           | amount = availableBalance + 1           | ON: APPROVED, responseCode="00"; OFF: DECLINED, responseCode="51", reason="INSUFFICIENT_FUNDS"    |
| expiryDate             | текущий месяц    | MMYY = текущий месяц                | MMYY = предыдущий месяц                 | ON: APPROVED, responseCode="00"; OFF: DECLINED, responseCode="54", reason="EXPIRED_CARD"          |
| dailyLimit usage       | dailyLimit       | dailyUsed + amount = dailyLimit     | dailyUsed + amount = dailyLimit + 1     | ON: APPROVED, responseCode="00"; OFF: DECLINED, responseCode="61", reason="EXCEEDS_DAILY_LIMIT"   |
| monthlyLimit usage     | monthlyLimit     | monthlyUsed + amount = monthlyLimit | monthlyUsed + amount = monthlyLimit + 1 | ON: APPROVED, responseCode="00"; OFF: DECLINED, responseCode="61", reason="EXCEEDS_MONTHLY_LIMIT" |

### Card Management Service

| Поле               | Граница            | ON                        | OFF                           | Ожидаемый результат                                                       |
|--------------------|--------------------|---------------------------|-------------------------------|---------------------------------------------------------------------------|
| amount (reserve)   | availableBalance   | amount = availableBalance | amount = availableBalance + 1 | ON: 200 OK, баланс = 0; OFF: 400 Bad Request, reason="INSUFFICIENT_FUNDS" |
| amount (reserve)   | минимальная сумма  | 1                         | 0                             | ON: 200 OK  ; OFF: 400 Bad Request, reason="INVALID_AMOUNT"               |

---

## 3. Попарное тестирование (pairwise)

### Модель PICT

**Authorization Service** — 7 параметров, 2 ограничения:
- card_status: ACTIVE, INACTIVE, BLOCKED, EXPIRED
- amount_vs_daily: below, equal, above
- amount_vs_monthly: below, equal, above
- amount_vs_balance: below, equal, above
- expiry: valid, current_month, expired
- terminal_type: pos, atm, ecom
- mcc: grocery, restaurant, electronics, travel

Ограничения:
- Если карта не ACTIVE (INACTIVE / BLOCKED / EXPIRED), то все три параметра суммы (amount_vs_daily, amount_vs_monthly, amount_vs_balance) автоматически принимают значение below.
Для неактивных карт нет смысла проверять лимиты и баланс, транзакция будет отклонена на этапе проверки статуса.
- Если срок карты истёк (expiry = expired), то все три параметра суммы (amount_vs_daily, amount_vs_monthly, amount_vs_balance) автоматически принимают значение below.
Для просроченной карты проверка лимитов и баланса не имеет смысла, транзакция отклонится по признаку EXPIRED_CARD до этапа финансовых проверок.

**Card Management Service** — 6 параметров, 3 ограничения:
- operation: create, read, update, delete, reserve, generate
- card_status: ACTIVE, INACTIVE, BLOCKED, EXPIRED
- pan_format: valid_luhn, invalid_luhn
- expiry: valid_future, expired
- bin: valid_400000, invalid_999999
- amount: within_balance, exceeds_balance, zero

Ограничения:
- Операция резервирования (reserve) возможна только для карт со статусом ACTIVE.
- При создании карты (create) формат PAN всегда valid_luhn.
Т.к. сервис сам генерирует PAN по алгоритму Луна.
- Параметр amount имеет смысл только для операции reserve. Для всех остальных операций он фиксирован как within_balance.
Так как суммы транзакции проверяются только при резервировании, для CRUD и generate этот параметр не применим.

### Обоснование применения 2-wise покрытия

Попарное тестирование выбрано как оптимальный баланс между полнотой покрытия и объёмом тестов. Полный перебор для Authorization составил бы 4×3×3×3×3×3×4 = 3888 комбинаций, для Card Management — 6×4×2×2×2×3 = 864 комбинации. Pairwise сокращает это до 26 и 25 тестов соответственно, гарантируя покрытие всех пар значений.

---

## 4. Тест-кейсы

### 4.1 Card Management Service

#### Тест-кейсы на классы эквивалентности

| ID          | Требование                 | Источник                       | Предусловие                    | Шаги                                                      | Ожидаемый результат                                         |
|-------------|----------------------------|--------------------------------|--------------------------------|-----------------------------------------------------------|-------------------------------------------------------------|
| CM-CE-001 с | Создание карты             | Класс: operation=create        | Сервис запущен, БД доступна    | 1. POST /api/cards с bin=400000, initialBalance=1000000   | 200 OK, карта создана с status=ACTIVE, PAN валидный по Luhn |
| CM-CE-002   | Чтение карты               | Класс: operation=read          | Карта ACTIVE создана           | 1. GET /api/cards/{pan}                                   | 200 OK, карта возвращена                                    |
| CM-CE-003   | Обновление карты           | Класс: operation=update        | Карта ACTIVE создана           | 1. PATCH /api/cards/{pan} с новым балансом                | 200 OK, баланс обновлён                                     |
| CM-CE-004   | Удаление карты             | Класс: operation=delete        | Карта ACTIVE создана           | 1. DELETE /api/cards/{pan}                                | 200 OK, статус карты = DELETED                              |
| CM-CE-005   | Резервирование средств     | Класс: operation=reserve       | Карта ACTIVE с балансом 100000 | 1. POST /api/cards/{pan}/reserve с amount=50000           | 200 OK, баланс уменьшен до 50000                            |
| CM-CE-006   | Генерация карт             | Класс: operation=generate      | Сервис запущен                 | 1. POST /api/cards/generate с count=100, bins=["400000"]  | 200 OK, 100 карт созданы, статусы распределены 95/3/2       |
| CM-CE-007   | Карта со статусом ACTIVE   | Класс: card_status=ACTIVE      | Карта ACTIVE создана           | 1. GET /api/cards/{pan}                                   | 200 OK, статус ACTIVE                                       |
| CM-CE-008   | Карта со статусом INACTIVE | Класс: card_status=INACTIVE    | Карта INACTIVE создана         | 1. GET /api/cards/{pan}                                   | 200 OK, статус INACTIVE                                     |
| CM-CE-009   | Карта со статусом BLOCKED  | Класс: card_status=BLOCKED     | Карта BLOCKED создана          | 1. GET /api/cards/{pan}                                   | 200 OK, статус BLOCKED                                      |
| CM-CE-010   | Карта со статусом EXPIRED  | Класс: card_status=EXPIRED     | Карта EXPIRED создана          | 1. GET /api/cards/{pan}                                   | 200 OK, статус EXPIRED                                      |
| CM-CE-011   | Валидный PAN по Luhn       | Класс: pan_format=valid_luhn   | Сервис запущен                 | 1. POST /api/cards с bin=400000                           | 200 OK, PAN проходит проверку Luhn                          |
| CM-CE-012   | Невалидный PAN по Luhn     | Класс: pan_format=invalid_luhn | Сервис запущен                 | 1. POST /api/cards с PAN, не проходящим Luhn              | 400 Bad Request, reason="INVALID_LUHN"                      |
| CM-CE-013   | Валидный срок действия     | Класс: expiry=valid_future     | Сервис запущен                 | 1. POST /api/cards с expiryDate через 3 года              | 200 OK, карта создана                                       |
| CM-CE-014   | Просроченный срок действия | Класс: expiry=expired          | Сервис запущен                 | 1. POST /api/cards с expiryDate в прошлом                 | 400 Bad Request, reason="EXPIRED_DATE"                      |
| CM-CE-015   | Валидный BIN               | Класс: bin=valid_400000        | Сервис запущен                 | 1. POST /api/cards с bin=400000                           | 200 OK, PAN начинается с 400000                             |
| CM-CE-016   | Невалидный BIN             | Класс: bin=invalid_999999      | Сервис запущен                 | 1. POST /api/cards с bin=999999                           | 400 Bad Request, reason="INVALID_BIN"                       |

#### Тест-кейсы на граничные значения

| ID          | Требование                       | Источник                               | Предусловие                     | Шаги                                               | Ожидаемый результат                          |
|-------------|----------------------------------|----------------------------------------|---------------------------------|----------------------------------------------------|----------------------------------------------|
| CM-BV-001   | amount равен балансу             | Граница: amount=availableBalance ON    | Карта ACTIVE с балансом 100000  | 1. POST /api/cards/{pan}/reserve с amount=100000   | 200 OK, баланс = 0                           |
| CM-BV-002   | amount превышает баланс          | Граница: amount=availableBalance+1 OFF | Карта ACTIVE с балансом 100000  | 1. POST /api/cards/{pan}/reserve с amount=100001   | 400 Bad Request, reason="INSUFFICIENT_FUNDS" |
| CM-BV-003   | amount равен нулю (невалидное)   | Граница: amount=0 OFF                  | Карта ACTIVE с балансом 100000  | 1. POST /api/cards/{pan}/reserve с amount=0        | 400 Bad Request, reason="INVALID_AMOUNT"     |
| CM-BV-004   | amount равен 1 (мин. допустимое) | Граница: amount=1 ON                   | Карта ACTIVE с балансом 100000  | 1. POST /api/cards/{pan}/reserve с amount=1        | 200 OK, баланс = 99999                       |

#### Тест-кейсы из попарного набора (pairwise)

| ID          | Требование                                                   | Источник            | Предусловие                                   | Шаги                                                     | Ожидаемый результат                                                          |
|-------------|--------------------------------------------------------------|---------------------|-----------------------------------------------|----------------------------------------------------------|------------------------------------------------------------------------------|
| CM-PW-001   | Удаление активной карты с невалидным PAN                     | Pairwise строка 1   | Карта ACTIVE создана                          | 1. DELETE /api/cards/{invalid_pan}                       | 404 Not Found, reason="CARD_NOT_FOUND"                                       |
| CM-PW-002   | Создание заблокированной карты с просроченной датой          | Pairwise строка 2   | Сервис запущен                                | 1. POST /api/cards с bin=400000, expiryDate в прошлом    | 400 Bad Request, reason="EXPIRED_DATE" (при создании статус всегда ACTIVE)   |
| CM-PW-003   | Создание просроченной карты с невалидным BIN                 | Pairwise строка 3   | Сервис запущен                                | 1. POST /api/cards с bin=999999, expiryDate в прошлом    | 400 Bad Request, reason="INVALID_BIN"                                        |
| CM-PW-004   | Удаление заблокированной карты с невалидным PAN              | Pairwise строка 4   | Карта BLOCKED создана                         | 1. DELETE /api/cards/{invalid_pan}                       | 404 Not Found, reason="CARD_NOT_FOUND"                                       |
| CM-PW-005   | Генерация неактивных карт с невалидным BIN                   | Pairwise строка 5   | Сервис запущен                                | 1. POST /api/cards/generate с bins=["999999"]            | 400 Bad Request, reason="INVALID_BIN"                                        |
| CM-PW-006   | Создание активной карты с просроченной датой                 | Pairwise строка 6   | Сервис запущен                                | 1. POST /api/cards с bin=400000, expiryDate в прошлом    | 400 Bad Request, reason="EXPIRED_DATE"                                       |
| CM-PW-007   | Генерация заблокированных карт с валидным BIN                | Pairwise строка 7   | Сервис запущен                                | 1. POST /api/cards/generate с count=100, bins=["400000"] | 200 OK, ~2% карт BLOCKED                                                     |
| CM-PW-008   | Резервирование нулевой суммы на карте с истёкшим сроком      | Pairwise строка 8   | Карта ACTIVE, expiry в прошлом                | 1. POST /api/cards/{pan}/reserve с amount=0              | 400 Bad Request, reason="INVALID_AMOUNT"                                     |
| CM-PW-009   | Чтение активной карты с валидным PAN                         | Pairwise строка 9   | Карта ACTIVE создана                          | 1. GET /api/cards/{pan}                                  | 200 OK, карта возвращена                                                     |
| CM-PW-010   | Обновление неактивной карты с валидным PAN                   | Pairwise строка 10  | Карта INACTIVE создана                        | 1. PATCH /api/cards/{pan} с новым балансом               | 200 OK, баланс обновлён                                                      |
| CM-PW-011   | Чтение неактивной карты с невалидным PAN                     | Pairwise строка 11  | Сервис запущен                                | 1. GET /api/cards/{invalid_pan}                          | 404 Not Found, reason="CARD_NOT_FOUND"                                       |
| CM-PW-012   | Удаление просроченной карты с валидным PAN                   | Pairwise строка 12  | Карта EXPIRED создана                         | 1. DELETE /api/cards/{pan}                               | 200 OK, статус карты = DELETED                                               |
| CM-PW-013   | Обновление просроченной карты с невалидным PAN               | Pairwise строка 13  | Карта EXPIRED создана                         | 1. PATCH /api/cards/{invalid_pan}                        | 404 Not Found, reason="CARD_NOT_FOUND"                                       |
| CM-PW-014   | Удаление неактивной карты с валидным PAN                     | Pairwise строка 14  | Карта INACTIVE создана                        | 1. DELETE /api/cards/{pan}                               | 200 OK, статус карты = DELETED                                               |
| CM-PW-015   | Генерация просроченных карт с валидным BIN                   | Pairwise строка 15  | Сервис запущен                                | 1. POST /api/cards/generate с count=100, bins=["400000"] | Ошибка: генератор создаёт карты с future expiry                              |
| CM-PW-016   | Генерация активных карт с просроченной датой                 | Pairwise строка 16  | Сервис запущен                                | 1. POST /api/cards/generate с count=100                  | Ошибка: генератор создаёт карты с future expiry                              |
| CM-PW-017   | Создание неактивной карты с валидным PAN                     | Pairwise строка 17  | Сервис запущен                                | 1. POST /api/cards с bin=400000, status=INACTIVE         | 400 Bad Request, reason="INVALID_STATUS" (при создании статус всегда ACTIVE) |
| CM-PW-018   | Чтение заблокированной карты с валидным PAN                  | Pairwise строка 18  | Карта BLOCKED создана                         | 1. GET /api/cards/{pan}                                  | 200 OK, карта возвращена со статусом BLOCKED                                 |
| CM-PW-019   | Резервирование сверх баланса на карте с истёкшим сроком      | Pairwise строка 19  | Карта ACTIVE, expiry в прошлом, баланс 100000 | 1. POST /api/cards/{pan}/reserve с amount=150000         | 400 Bad Request, reason="INSUFFICIENT_FUNDS"                                 |
| CM-PW-020   | Чтение просроченной карты с валидным PAN                     | Pairwise строка 20  | Карта EXPIRED создана                         | 1. GET /api/cards/{pan}                                  | 200 OK, карта возвращена со статусом EXPIRED                                 |
| CM-PW-021   | Резервирование нулевой суммы на активной карте               | Pairwise строка 21  | Карта ACTIVE с балансом 100000                | 1. POST /api/cards/{pan}/reserve с amount=0              | 400 Bad Request, reason="INVALID_AMOUNT"                                     |
| CM-PW-022   | Обновление заблокированной карты с невалидным PAN            | Pairwise строка 22  | Карта BLOCKED создана                         | 1. PATCH /api/cards/{invalid_pan}                        | 404 Not Found, reason="CARD_NOT_FOUND"                                       |
| CM-PW-023   | Резервирование в пределах баланса на карте с истёкшим сроком | Pairwise строка 23  | Карта ACTIVE, expiry в прошлом, баланс 100000 | 1. POST /api/cards/{pan}/reserve с amount=50000          | 200 OK, баланс уменьшен до 50000                                             |
| CM-PW-024   | Резервирование сверх баланса с невалидным PAN                | Pairwise строка 24  | Карта ACTIVE создана                          | 1. POST /api/cards/{invalid_pan}/reserve с amount=150000 | 404 Not Found, reason="CARD_NOT_FOUND"                                       |
| CM-PW-025   | Обновление активной карты с просроченной датой               | Pairwise строка 25  | Карта ACTIVE, expiry в прошлом                | 1. PATCH /api/cards/{pan} с новым балансом               | 200 OK, баланс обновлён                                                      |

---

### 4.2 Authorization Service

#### Тест-кейсы на классы эквивалентности

| ID            | Требование               | Источник                      | Предусловие                     | Шаги                                                            | Ожидаемый результат                                 |
|---------------|--------------------------|-------------------------------|---------------------------------|-----------------------------------------------------------------|-----------------------------------------------------|
| AUTH-CE-001   | Неактивная карта         | Класс: card_status=INACTIVE   | Карта INACTIVE, баланс 100000   | 1. POST /api/internal/authorize с amount=50000                  | DECLINED, responseCode="05", reason="CARD_INACTIVE" |
| AUTH-CE-002   | Заблокированная карта    | Класс: card_status=BLOCKED    | Карта BLOCKED, баланс 100000    | 1. POST /api/internal/authorize с amount=50000                  | DECLINED, responseCode="05", reason="CARD_BLOCKED"  |
| AUTH-CE-003   | Просроченная карта       | Класс: card_status=EXPIRED    | Карта EXPIRED                   | 1. POST /api/internal/authorize с amount=50000                  | DECLINED, responseCode="54", reason="EXPIRED_CARD"  |
| AUTH-CE-004   | Активная карта (базовый) | Класс: card_status=ACTIVE     | Карта ACTIVE, sufficient limits | 1. POST /api/internal/authorize с amount=50000                  | APPROVED, responseCode="00"                         |
| AUTH-CE-005   | POS-терминал             | Класс: terminal_type=pos      | Карта ACTIVE                    | 1. POST /api/internal/authorize с amount=50000, terminal=pos    | APPROVED, responseCode="00"                         |
| AUTH-CE-006   | ATM-терминал             | Класс: terminal_type=atm      | Карта ACTIVE                    | 1. POST /api/internal/authorize с amount=50000, terminal=atm    | APPROVED, responseCode="00"                         |
| AUTH-CE-007   | ECOM-терминал            | Класс: terminal_type=ecom     | Карта ACTIVE                    | 1. POST /api/internal/authorize с amount=50000, terminal=ecom   | APPROVED, responseCode="00"                         |
| AUTH-CE-008   | MCC grocery              | Класс: mcc=grocery            | Карта ACTIVE                    | 1. POST /api/internal/authorize с amount=50000, mcc=grocery     | APPROVED, responseCode="00"                         |
| AUTH-CE-009   | MCC restaurant           | Класс: mcc=restaurant         | Карта ACTIVE                    | 1. POST /api/internal/authorize с amount=50000, mcc=restaurant  | APPROVED, responseCode="00"                         |
| AUTH-CE-010   | MCC electronics          | Класс: mcc=electronics        | Карта ACTIVE                    | 1. POST /api/internal/authorize с amount=50000, mcc=electronics | APPROVED, responseCode="00"                         |
| AUTH-CE-011   | MCC travel               | Класс: mcc=travel             | Карта ACTIVE                    | 1. POST /api/internal/authorize с amount=50000, mcc=travel      | APPROVED, responseCode="00"                         |

#### Тест-кейсы на граничные значения

| ID           | Требование                           | Источник                                       | Предусловие                                                          | Шаги                                              | Ожидаемый результат                                         |
|--------------|--------------------------------------|------------------------------------------------|----------------------------------------------------------------------|---------------------------------------------------|-------------------------------------------------------------|
| AUTH-BV-001  | amount равен dailyLimit              | Граница: amount=dailyLimit ON                  | Карта ACTIVE, dailyLimit=100000, monthlyLimit=200000, balance=200000 | 1. POST /api/internal/authorize с amount=100000   | APPROVED, responseCode="00"                                 |
| AUTH-BV-002  | amount превышает dailyLimit          | Граница: amount=dailyLimit+1 OFF               | Карта ACTIVE, dailyLimit=100000, monthlyLimit=200000, balance=200000 | 1. POST /api/internal/authorize с amount=100001   | DECLINED, responseCode="61", reason="EXCEEDS_DAILY_LIMIT"   |
| AUTH-BV-003  | amount равен monthlyLimit            | Граница: amount=monthlyLimit ON                | Карта ACTIVE, dailyLimit=200000, monthlyLimit=100000, balance=200000 | 1. POST /api/internal/authorize с amount=100000   | APPROVED, responseCode="00"                                 |
| AUTH-BV-004  | amount превышает monthlyLimit        | Граница: amount=monthlyLimit+1 OFF             | Карта ACTIVE, dailyLimit=200000, monthlyLimit=100000, balance=200000 | 1. POST /api/internal/authorize с amount=100001   | DECLINED, responseCode="61", reason="EXCEEDS_MONTHLY_LIMIT" |
| AUTH-BV-005  | amount равен availableBalance        | Граница: amount=availableBalance ON            | Карта ACTIVE, balance=100000                                         | 1. POST /api/internal/authorize с amount=100000   | APPROVED, responseCode="00", баланс=0                       |
| AUTH-BV-006  | amount превышает availableBalance    | Граница: amount=availableBalance+1 OFF         | Карта ACTIVE, balance=100000                                         | 1. POST /api/internal/authorize с amount=100001   | DECLINED, responseCode="51", reason="INSUFFICIENT_FUNDS"    |
| AUTH-BV-007  | expiryDate текущий месяц             | Граница: expiry=current_month ON               | Карта ACTIVE, expiry=текущий MMYY                                    | 1. POST /api/internal/authorize с amount=50000    | APPROVED, responseCode="00"                                 |
| AUTH-BV-008  | expiryDate прошлый месяц             | Граница: expiry=прошлый MMYY OFF               | Карта ACTIVE, expiry=прошлый MMYY                                    | 1. POST /api/internal/authorize с amount=50000    | DECLINED, responseCode="54", reason="EXPIRED_CARD"          |
| AUTH-BV-009  | dailyLimit usage на границе          | Граница: dailyUsed+amount=dailyLimit ON        | Карта ACTIVE, dailyUsed=50000, dailyLimit=100000                     | 1. POST /api/internal/authorize с amount=50000    | APPROVED, responseCode="00"                                 |
| AUTH-BV-010  | dailyLimit usage превышает границу   | Граница: dailyUsed+amount=dailyLimit+1 OFF     | Карта ACTIVE, dailyUsed=50001, dailyLimit=100000                     | 1. POST /api/internal/authorize с amount=50000    | DECLINED, responseCode="61", reason="EXCEEDS_DAILY_LIMIT"   |
| AUTH-BV-011  | monthlyLimit usage на границе        | Граница: monthlyUsed+amount=monthlyLimit ON    | Карта ACTIVE, monthlyUsed=50000, monthlyLimit=100000                 | 1. POST /api/internal/authorize с amount=50000    | APPROVED, responseCode="00"                                 |
| AUTH-BV-012  | monthlyLimit usage превышает границу | Граница: monthlyUsed+amount=monthlyLimit+1 OFF | Карта ACTIVE, monthlyUsed=50001, monthlyLimit=100000                 | 1. POST /api/internal/authorize с amount=50000    | DECLINED, responseCode="61", reason="EXCEEDS_MONTHLY_LIMIT" |

#### Тест-кейсы из попарного набора (pairwise)

| ID            | Требование                                                                           | Источник           | Предусловие                                                                        | Шаги                                                                            | Ожидаемый результат                                                                           |
|---------------|--------------------------------------------------------------------------------------|--------------------|------------------------------------------------------------------------------------|---------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------|
| AUTH-PW-001   | Неактивная карта (pos, electronics)                                                  | Pairwise строка 1  | Карта INACTIVE                                                                     | 1. POST /api/internal/authorize с amount=50000, terminal=pos, mcc=electronics   | DECLINED, responseCode="05", reason="CARD_INACTIVE"                                           |
| AUTH-PW-002   | Превышение месячного и баланса                                                       | Pairwise строка 2  | Карта ACTIVE, monthlyLimit=100000, balance=100000                                  | 1. POST /api/internal/authorize с amount=150000, terminal=ecom, mcc=travel      | DECLINED, responseCode="61", reason="EXCEEDS_MONTHLY_LIMIT" (проверка monthly раньше balance) |
| AUTH-PW-003   | Просроченная карта (atm, grocery)                                                    | Pairwise строка 3  | Карта EXPIRED                                                                      | 1. POST /api/internal/authorize с amount=50000, terminal=atm, mcc=grocery       | DECLINED, responseCode="54", reason="EXPIRED_CARD"                                            |
| AUTH-PW-004   | Превышение всех лимитов                                                              | Pairwise строка 4  | Карта ACTIVE, dailyLimit=100000, monthlyLimit=100000, balance=100000               | 1. POST /api/internal/authorize с amount=150000, terminal=pos, mcc=grocery      | DECLINED, responseCode="61", reason="EXCEEDS_DAILY_LIMIT" (daily проверяется первым)          |
| AUTH-PW-005   | Заблокированная карта (atm, restaurant)                                              | Pairwise строка 5  | Карта BLOCKED                                                                      | 1. POST /api/internal/authorize с amount=50000, terminal=atm, mcc=restaurant    | DECLINED, responseCode="05", reason="CARD_BLOCKED"                                            |
| AUTH-PW-006   | Неактивная карта (ecom, grocery)                                                     | Pairwise строка 6  | Карта INACTIVE                                                                     | 1. POST /api/internal/authorize с amount=50000, terminal=ecom, mcc=grocery      | DECLINED, responseCode="05", reason="CARD_INACTIVE"                                           |
| AUTH-PW-007   | Неактивная карта (atm, restaurant, expired)                                          | Pairwise строка 7  | Карта INACTIVE, expired                                                            | 1. POST /api/internal/authorize с amount=50000, terminal=atm, mcc=restaurant    | DECLINED, responseCode="05", reason="CARD_INACTIVE" (статус проверяется перед expiry)         |
| AUTH-PW-008   | Заблокированная карта (ecom, electronics, expired)                                   | Pairwise строка 8  | Карта BLOCKED, expired                                                             | 1. POST /api/internal/authorize с amount=50000, terminal=ecom, mcc=electronics  | DECLINED, responseCode="05", reason="CARD_BLOCKED"                                            |
| AUTH-PW-009   | Заблокированная карта (pos, travel, expired)                                         | Pairwise строка 9  | Карта BLOCKED, expired                                                             | 1. POST /api/internal/authorize с amount=50000, terminal=pos, mcc=travel        | DECLINED, responseCode="05", reason="CARD_BLOCKED"                                            |
| AUTH-PW-010   | Равенство лимитам и балансу (atm, electronics)                                       | Pairwise строка 10 | Карта ACTIVE, dailyLimit=100000, monthlyLimit=100000, balance=100000               | 1. POST /api/internal/authorize с amount=100000, terminal=atm, mcc=electronics  | APPROVED, responseCode="00"                                                                   |
| AUTH-PW-011   | Равенство всем лимитам (current_month, pos, travel)                                  | Pairwise строка 11 | Карта ACTIVE, все лимиты=100000, current_month                                     | 1. POST /api/internal/authorize с amount=100000, terminal=pos, mcc=travel       | APPROVED, responseCode="00"                                                                   |
| AUTH-PW-012   | Превышение дневного, равенство остальным (ecom, restaurant)                          | Pairwise строка 12 | Карта ACTIVE, dailyLimit=100000, monthlyLimit=100000, balance=100000               | 1. POST /api/internal/authorize с amount=150000, terminal=ecom, mcc=restaurant  | DECLINED, responseCode="61", reason="EXCEEDS_DAILY_LIMIT"                                     |
| AUTH-PW-013   | Просроченная карта (current_month, ecom, electronics)                                | Pairwise строка 13 | Карта EXPIRED, current_month                                                       | 1. POST /api/internal/authorize с amount=50000, terminal=ecom, mcc=electronics  | DECLINED, responseCode="54", reason="EXPIRED_CARD"                                            |
| AUTH-PW-014   | Равенство дневному, ниже месячного, выше баланса (pos, restaurant)                   | Pairwise строка 14 | Карта ACTIVE, dailyLimit=100000, monthlyLimit=200000, balance=50000                | 1. POST /api/internal/authorize с amount=100000, terminal=pos, mcc=restaurant   | DECLINED, responseCode="51", reason="INSUFFICIENT_FUNDS"                                      |
| AUTH-PW-015   | Просроченная карта (valid, pos, restaurant)                                          | Pairwise строка 15 | Карта EXPIRED, valid expiry                                                        | 1. POST /api/internal/authorize с amount=50000, terminal=pos, mcc=restaurant    | DECLINED, responseCode="54", reason="EXPIRED_CARD"                                            |
| AUTH-PW-016   | Равенство дневному, ниже месячного, выше баланса (expired, pos, grocery)             | Pairwise строка 16 | Карта ACTIVE, expired, dailyLimit=100000, balance=50000                            | 1. POST /api/internal/authorize с amount=100000, terminal=pos, mcc=grocery      | DECLINED, responseCode="54", reason="EXPIRED_CARD" (expiry проверяется раньше лимитов)        |
| AUTH-PW-017   | Превышение дневного, равенство месячному, выше баланса (atm, travel)                 | Pairwise строка 17 | Карта ACTIVE, dailyLimit=100000, monthlyLimit=100000, balance=50000                | 1. POST /api/internal/authorize с amount=150000, terminal=atm, mcc=travel       | DECLINED, responseCode="61", reason="EXCEEDS_DAILY_LIMIT"                                     |
| AUTH-PW-018   | Превышение дневного и месячного, равенство балансу (atm, restaurant)                 | Pairwise строка 18 | Карта ACTIVE, dailyLimit=100000, monthlyLimit=100000, balance=100000               | 1. POST /api/internal/authorize с amount=150000, terminal=atm, mcc=restaurant   | DECLINED, responseCode="61", reason="EXCEEDS_DAILY_LIMIT"                                     |
| AUTH-PW-019   | Неактивная карта (expired, ecom, travel)                                             | Pairwise строка 19 | Карта INACTIVE, expired                                                            | 1. POST /api/internal/authorize с amount=50000, terminal=ecom, mcc=travel       | DECLINED, responseCode="05", reason="CARD_INACTIVE"                                           |
| AUTH-PW-020   | Превышение дневного, равенство месячному, ниже баланса (expired, ecom, electronics)  | Pairwise строка 20 | Карта ACTIVE, expired, dailyLimit=100000, balance=200000                           | 1. POST /api/internal/authorize с amount=150000, terminal=ecom, mcc=electronics | DECLINED, responseCode="54", reason="EXPIRED_CARD"                                            |
| AUTH-PW-021   | Ниже дневного, равенство месячному, выше баланса (ecom, grocery)                     | Pairwise строка 21 | Карта ACTIVE, monthlyLimit=100000, balance=50000                                   | 1. POST /api/internal/authorize с amount=100000, terminal=ecom, mcc=grocery     | DECLINED, responseCode="51", reason="INSUFFICIENT_FUNDS"                                      |
| AUTH-PW-022   | Просроченная карта (current_month, ecom, travel)                                     | Pairwise строка 22 | Карта EXPIRED, current_month                                                       | 1. POST /api/internal/authorize с amount=50000, terminal=ecom, mcc=travel       | DECLINED, responseCode="54", reason="EXPIRED_CARD"                                            |
| AUTH-PW-023   | Ниже дневного, равенство месячному и балансу (ecom, electronics)                     | Pairwise строка 23 | Карта ACTIVE, monthlyLimit=100000, balance=100000                                  | 1. POST /api/internal/authorize с amount=100000, terminal=ecom, mcc=electronics | APPROVED, responseCode="00"                                                                   |
| AUTH-PW-024   | Равенство всем лимитам, ниже баланса (ecom, restaurant)                              | Pairwise строка 24 | Карта ACTIVE, dailyLimit=100000, monthlyLimit=100000, balance=200000               | 1. POST /api/internal/authorize с amount=100000, terminal=ecom, mcc=restaurant  | APPROVED, responseCode="00"                                                                   |
| AUTH-PW-025   | Заблокированная карта (valid, atm, grocery)                                          | Pairwise строка 25 | Карта BLOCKED                                                                      | 1. POST /api/internal/authorize с amount=50000, terminal=atm, mcc=grocery       | DECLINED, responseCode="05", reason="CARD_BLOCKED"                                            |
| AUTH-PW-026   | Превышение дневного, равенство месячному, выше баланса (current_month, atm, grocery) | Pairwise строка 26 | Карта ACTIVE, current_month, dailyLimit=100000, monthlyLimit=100000, balance=50000 | 1. POST /api/internal/authorize с amount=150000, terminal=atm, mcc=grocery      | DECLINED, responseCode="61", reason="EXCEEDS_DAILY_LIMIT"                                     |
