package com.processing.apitests;

import com.processing.apitests.support.AuthRequestBuilder;
import com.processing.apitests.support.CardApi;
import com.processing.apitests.support.Specs;
import com.processing.apitests.support.TestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;

/**
 * Внутренние эндпоинты цепочки авторизации:
 * POST /api/internal/route (switch) и POST /api/internal/authorize (authorization).
 * Источник ожиданий: docs/api-spec.md раздел 7, RouteController, AuthControllerImpl
 * (HTTP-статусы отказа: 404/403/422/400/503).
 */
class InternalRouteAuthorizeApiTest {

    @Test
    @DisplayName("switch /api/internal/route одобряет валидную транзакцию и подставляет issuerId")
    void switchRouteApproves() {
        String pan = CardApi.createActiveCard();
        AuthRequestBuilder request = AuthRequestBuilder.valid(pan);

        given()
                .spec(Specs.switchService())
                .body(request.build())
        .when()
                .post("/api/internal/route")
        .then()
                .statusCode(200)
                .body(matchesJsonSchemaInClasspath("schemas/authorization-response.json"))
                .body("status", equalTo("APPROVED"))
                .body("responseCode", equalTo("00"))
                .body("stan", equalTo(request.stan()))
                .body("rrn", matchesPattern("\\d{12}"));
    }

    @Test
    @DisplayName("switch /api/internal/route для неизвестного BIN: 200, DECLINED 14/CARD_NOT_FOUND")
    void switchRouteUnknownBinDeclines() {
        AuthRequestBuilder request = AuthRequestBuilder.valid(TestData.panWithUnknownBin());

        given()
                .spec(Specs.switchService())
                .body(request.build())
        .when()
                .post("/api/internal/route")
        .then()
                .statusCode(200)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("14"))
                .body("declineReason", equalTo("CARD_NOT_FOUND"));
    }

    @Test
    @DisplayName("authorization /api/internal/authorize одобряет запрос с issuerId: 200")
    void authorizationApproves() {
        String pan = CardApi.createActiveCard();
        AuthRequestBuilder request = AuthRequestBuilder.valid(pan).with("issuerId", "ISS001");

        given()
                .spec(Specs.authorization())
                .body(request.build())
        .when()
                .post("/api/internal/authorize")
        .then()
                .statusCode(200)
                .body(matchesJsonSchemaInClasspath("schemas/authorization-response.json"))
                .body("status", equalTo("APPROVED"))
                .body("responseCode", equalTo("00"))
                .body("authCode", matchesPattern("[A-Z0-9]{6}"))
                .body("declineReason", nullValue());
    }

    @Test
    @DisplayName("authorization: карта не найдена → 404, код 14, причина CARD_NOT_FOUND")
    void authorizationCardNotFoundReturns404() {
        AuthRequestBuilder request = AuthRequestBuilder.valid(TestData.panWithoutCard())
                .with("issuerId", "ISS001");

        given()
                .spec(Specs.authorization())
                .body(request.build())
        .when()
                .post("/api/internal/authorize")
        .then()
                .statusCode(404)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("14"))
                .body("declineReason", equalTo("CARD_NOT_FOUND"));
    }

    @Test
    @DisplayName("authorization: заблокированная карта → 403, код 05, причина CARD_BLOCKED")
    void authorizationBlockedCardReturns403() {
        String pan = CardApi.createActiveCard();
        CardApi.changeStatus(pan, "BLOCKED");

        given()
                .spec(Specs.authorization())
                .body(AuthRequestBuilder.valid(pan).with("issuerId", "ISS001").build())
        .when()
                .post("/api/internal/authorize")
        .then()
                .statusCode(403)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("05"))
                .body("declineReason", equalTo("CARD_BLOCKED"));
    }

    @Test
    @DisplayName("authorization: недостаточно средств → 422, код 51, причина INSUFFICIENT_FUNDS")
    void authorizationInsufficientFundsReturns422() {
        String pan = CardApi.createActiveCard(TestData.KNOWN_BIN, 100L, 1_000_000L, 10_000_000L);

        given()
                .spec(Specs.authorization())
                .body(AuthRequestBuilder.valid(pan)
                        .with("issuerId", "ISS001")
                        .with("amount", 500_000L)
                        .build())
        .when()
                .post("/api/internal/authorize")
        .then()
                .statusCode(422)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("51"))
                .body("declineReason", equalTo("INSUFFICIENT_FUNDS"));
    }

    @Test
    @DisplayName("authorization: превышение лимита → 400, код 61, причина EXCEEDS_AMOUNT_LIMIT")
    void authorizationExceedsLimitReturns400() {
        String pan = CardApi.createActiveCard(TestData.KNOWN_BIN, 10_000_000L, 500L, 10_000_000L);

        given()
                .spec(Specs.authorization())
                .body(AuthRequestBuilder.valid(pan).with("issuerId", "ISS001").with("amount", 400L).build())
        .when()
                .post("/api/internal/authorize")
        .then()
                .statusCode(200)
                .body("status", equalTo("APPROVED"));

        given()
                .spec(Specs.authorization())
                .body(AuthRequestBuilder.valid(pan).with("issuerId", "ISS001").with("amount", 400L).build())
        .when()
                .post("/api/internal/authorize")
        .then()
                // Исход EXCEEDS_AMOUNT_LIMIT отображается в HTTP 400 (default-ветка
                // AuthControllerImpl) — спецификация такой статус для отказа не описывает,
                // см. отчёт, раздел 4.
                .statusCode(400)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("61"))
                .body("declineReason", equalTo("EXCEEDS_AMOUNT_LIMIT"));
    }

    @Test
    @DisplayName("authorization: запрос без обязательного поля → 400")
    void authorizationMissingFieldReturns400() {
        given()
                .spec(Specs.authorization())
                .body(AuthRequestBuilder.valid(TestData.panWithoutCard()).without("stan").build())
        .when()
                .post("/api/internal/authorize")
        .then()
                .statusCode(400);
    }
}
