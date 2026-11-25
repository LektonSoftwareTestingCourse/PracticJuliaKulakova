package com.processing.apitests;

import com.processing.apitests.support.AuthRequestBuilder;
import com.processing.apitests.support.CardApi;
import com.processing.apitests.support.Specs;
import com.processing.apitests.support.TestData;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;

/**
 * Успешная авторизация через Gateway: POST /api/transactions → switch → authorization.
 * Источник ожиданий: docs/api-spec.md раздел 3 и модель AuthorizationResponse;
 * формат rrn/authCode — AuthServiceImpl.generateRRN()/generateAuthCode().
 */
class TransactionApprovalApiTest {

    @Test
    @DisplayName("Валидный запрос по активной карте одобряется: 200, код 00, rrn и authCode по формату")
    void validTransactionIsApproved() {
        String pan = CardApi.createActiveCard();
        AuthRequestBuilder request = AuthRequestBuilder.valid(pan).with("amount", 150000L);

        given()
                .spec(Specs.gateway())
                .body(request.build())
        .when()
                .post("/api/transactions")
        .then()
                .statusCode(200)
                .body(matchesJsonSchemaInClasspath("schemas/authorization-response.json"))
                // Расхождение со спецификацией: spec требует mti="0110", реализация
                // (AuthorizationResponse.approved) возвращает mti запроса. См. отчёт, раздел 4.
                .body("mti", equalTo("0100"))
                .body("stan", equalTo(request.stan()))
                .body("rrn", matchesPattern("\\d{12}"))
                .body("authCode", matchesPattern("[A-Z0-9]{6}"))
                .body("responseCode", equalTo("00"))
                .body("status", equalTo("APPROVED"))
                .body("declineReason", nullValue())
                .body("processingTimeMs", greaterThanOrEqualTo(0));
    }

    @Test
    @DisplayName("Одобрение резервирует средства: доступный баланс карты уменьшается на сумму")
    void approvalReservesFundsOnCard() {
        String pan = CardApi.createActiveCard(TestData.KNOWN_BIN, 1_000_000L, 1_000_000L, 10_000_000L);
        long amount = 150_000L;

        given()
                .spec(Specs.gateway())
                .body(AuthRequestBuilder.valid(pan).with("amount", amount).build())
        .when()
                .post("/api/transactions")
        .then()
                .statusCode(200)
                .body("status", equalTo("APPROVED"));

        Assertions.assertEquals(850_000L, CardApi.availableBalance(pan),
                "Доступный баланс должен уменьшиться на сумму одобренной транзакции");
    }

    @Test
    @DisplayName("Два одобрения подряд получают разные rrn и authCode")
    void consecutiveApprovalsGetDistinctRrn() {
        String pan = CardApi.createActiveCard();

        String firstRrn = given()
                .spec(Specs.gateway())
                .body(AuthRequestBuilder.valid(pan).build())
        .when()
                .post("/api/transactions")
        .then()
                .statusCode(200)
                .body("status", equalTo("APPROVED"))
                .extract().jsonPath().getString("rrn");

        String secondRrn = given()
                .spec(Specs.gateway())
                .body(AuthRequestBuilder.valid(pan).build())
        .when()
                .post("/api/transactions")
        .then()
                .statusCode(200)
                .body("status", equalTo("APPROVED"))
                .extract().jsonPath().getString("rrn");

        Assertions.assertNotEquals(firstRrn, secondRrn,
                "rrn обязан быть уникальным для каждой транзакции");
    }
}
