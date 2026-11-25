package com.processing.apitests;

import com.processing.apitests.support.AuthRequestBuilder;
import com.processing.apitests.support.CardApi;
import com.processing.apitests.support.Specs;
import com.processing.apitests.support.TestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static io.restassured.RestAssured.given;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * Бизнес-отказы авторизации через Gateway (POST /api/transactions).
 * Gateway всегда возвращает HTTP 200, исход определяется доменными полями
 * responseCode + declineReason + status.
 * Источник ожиданий: алгоритм AuthServiceImpl.authorize() и DeclineOutcome;
 * коды — docs/api-spec.md, таблица «Коды ответов».
 */
class TransactionDeclineApiTest {

    @Test
    @DisplayName("Карта не найдена (PAN без карты, BIN известен): 14 / CARD_NOT_FOUND")
    void cardNotFoundDeclined() {
        AuthRequestBuilder request = AuthRequestBuilder.valid(TestData.panWithoutCard());

        given()
                .spec(Specs.gateway())
                .body(request.build())
        .when()
                .post("/api/transactions")
        .then()
                .statusCode(200)
                .body(matchesJsonSchemaInClasspath("schemas/authorization-response.json"))
                .body("stan", equalTo(request.stan()))
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("14"))
                .body("declineReason", equalTo("CARD_NOT_FOUND"))
                .body("rrn", nullValue())
                .body("authCode", nullValue());
    }

    @Test
    @DisplayName("Неизвестный BIN: switch отклоняет без обращения к authorization, 14 / CARD_NOT_FOUND")
    void unknownBinDeclinedBySwitch() {
        AuthRequestBuilder request = AuthRequestBuilder.valid(TestData.panWithUnknownBin());

        given()
                .spec(Specs.gateway())
                .body(request.build())
        .when()
                .post("/api/transactions")
        .then()
                .statusCode(200)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("14"))
                // Тот же код и причина, что и при отсутствующей карте, но путь другой:
                // отказ формирует switch (RoutingService, UnknownBinException),
                // а не authorization. Различие путей зафиксировано в отчёте, раздел 2.
                .body("declineReason", equalTo("CARD_NOT_FOUND"))
                .body("rrn", nullValue())
                .body("authCode", nullValue());
    }

    @ParameterizedTest(name = "статус карты {0} → код {1}, причина {2}")
    @CsvSource({
            "BLOCKED, 05, CARD_BLOCKED",
            "INACTIVE, 05, CARD_INACTIVE",
            "EXPIRED, 54, CARD_EXPIRED"
    })
    @DisplayName("Неактивные статусы карты отклоняются: код и причина проверяются совместно")
    void nonActiveCardStatusDeclined(String cardStatus, String expectedCode, String expectedReason) {
        String pan = CardApi.createActiveCard();
        CardApi.changeStatus(pan, cardStatus);

        given()
                .spec(Specs.gateway())
                .body(AuthRequestBuilder.valid(pan).build())
        .when()
                .post("/api/transactions")
        .then()
                .statusCode(200)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo(expectedCode))
                .body("declineReason", equalTo(expectedReason));
    }

    @Test
    @DisplayName("Просроченная дата (путь через данные запроса): transmissionDateTime позже expiryDate → 54 / CARD_EXPIRED")
    void expiredByTransmissionDateDeclined() {
        String pan = CardApi.createActiveCard();

        given()
                .spec(Specs.gateway())
                .body(AuthRequestBuilder.valid(pan)
                        .with("transmissionDateTime", "2099-01-01T00:00:00Z")
                        .build())
        .when()
                .post("/api/transactions")
        .then()
                .statusCode(200)
                .body("status", equalTo("DECLINED"))
                // Исход CARD_EXPIRED достижим двумя путями: статус карты EXPIRED
                // (состояние карты) и будущая дата передачи (данные запроса).
                // Оба пути проверены: см. nonActiveCardStatusDeclined[EXPIRED].
                .body("responseCode", equalTo("54"))
                .body("declineReason", equalTo("CARD_EXPIRED"));
    }

    @Test
    @DisplayName("Недостаточно средств (путь через данные запроса): amount больше баланса → 51 / INSUFFICIENT_FUNDS")
    void insufficientFundsByAmountDeclined() {
        String pan = CardApi.createActiveCard(TestData.KNOWN_BIN, 1_000L, 1_000_000L, 10_000_000L);

        given()
                .spec(Specs.gateway())
                .body(AuthRequestBuilder.valid(pan).with("amount", 5_000L).build())
        .when()
                .post("/api/transactions")
        .then()
                .statusCode(200)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("51"))
                .body("declineReason", equalTo("INSUFFICIENT_FUNDS"));
    }

    @Test
    @DisplayName("Недостаточно средств (путь через состояние карты): нулевой баланс → 51 / INSUFFICIENT_FUNDS")
    void insufficientFundsByZeroBalanceDeclined() {
        String pan = CardApi.createActiveCard(TestData.KNOWN_BIN, 0L, 1_000_000L, 10_000_000L);

        given()
                .spec(Specs.gateway())
                .body(AuthRequestBuilder.valid(pan).with("amount", 1_000L).build())
        .when()
                .post("/api/transactions")
        .then()
                .statusCode(200)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("51"))
                .body("declineReason", equalTo("INSUFFICIENT_FUNDS"));
    }

    @Test
    @DisplayName("Превышение дневного лимита: вторая транзакция за день → 61 / EXCEEDS_AMOUNT_LIMIT")
    void exceedsDailyLimitDeclined() {
        String pan = CardApi.createActiveCard(TestData.KNOWN_BIN, 10_000_000L, 1_000L, 10_000_000L);

        given()
                .spec(Specs.gateway())
                .body(AuthRequestBuilder.valid(pan).with("amount", 600L).build())
        .when()
                .post("/api/transactions")
        .then()
                .statusCode(200)
                .body("status", equalTo("APPROVED"));

        given()
                .spec(Specs.gateway())
                .body(AuthRequestBuilder.valid(pan).with("amount", 600L).build())
        .when()
                .post("/api/transactions")
        .then()
                .statusCode(200)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("61"))
                .body("declineReason", equalTo("EXCEEDS_AMOUNT_LIMIT"));
    }

    @Test
    @DisplayName("Превышение месячного лимита при большом дневном → 61 / EXCEEDS_AMOUNT_LIMIT")
    void exceedsMonthlyLimitDeclined() {
        String pan = CardApi.createActiveCard(TestData.KNOWN_BIN, 10_000_000L, 1_000_000L, 1_000L);

        given()
                .spec(Specs.gateway())
                .body(AuthRequestBuilder.valid(pan).with("amount", 600L).build())
        .when()
                .post("/api/transactions")
        .then()
                .statusCode(200)
                .body("status", equalTo("APPROVED"));

        given()
                .spec(Specs.gateway())
                .body(AuthRequestBuilder.valid(pan).with("amount", 600L).build())
        .when()
                .post("/api/transactions")
        .then()
                .statusCode(200)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("61"))
                .body("declineReason", equalTo("EXCEEDS_AMOUNT_LIMIT"));
    }

    @ParameterizedTest(name = "amount={0} отклоняется Gateway до маршрутизации")
    @ValueSource(longs = {0L, -1L})
    @DisplayName("Неположительная сумма не проходит валидацию Gateway: 400, транзакция не маршрутизируется")
    void nonPositiveAmountRejectedByGateway(long amount) {
        String pan = CardApi.createActiveCard();

        given()
                .spec(Specs.gateway())
                .body(AuthRequestBuilder.valid(pan).with("amount", amount).build())
        .when()
                .post("/api/transactions")
        .then()
                .statusCode(400)
                .body("error", equalTo("VALIDATION_ERROR"))
                .body("serviceName", equalTo("gateway"))
                .body("message", equalTo("Field 'amount' must be > 0"));
    }
}
