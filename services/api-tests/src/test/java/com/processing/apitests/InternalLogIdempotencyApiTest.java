package com.processing.apitests;

import com.processing.apitests.support.Specs;
import com.processing.apitests.support.TestData;
import com.processing.apitests.support.TransactionLogBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Повторная доставка одного и того же сообщения: POST /api/internal/log (transaction-logger).
 * Источник ожиданий: InternalTransactionController (201/200/409/400) и
 * TransactionService.store() + TransactionMapper.matches() (идемпотентность).
 */
class InternalLogIdempotencyApiTest {

    @Test
    @DisplayName("Первая доставка: 201 и тело {id, status=stored}")
    void firstDeliveryReturns201Stored() {
        TransactionLogBuilder transaction = TransactionLogBuilder.approved(TestData.panWithoutCard());

        given()
                .spec(Specs.logger())
                .body(transaction.build())
        .when()
                .post("/api/internal/log")
        .then()
                .statusCode(201)
                .body("id", equalTo(transaction.id()))
                .body("status", equalTo("stored"));
    }

    @Test
    @DisplayName("Повторная доставка тех же данных: 200 и полная существующая запись, дубликат не создаётся")
    void redeliverySameDataReturns200Existing() {
        TransactionLogBuilder transaction = TransactionLogBuilder.approved(TestData.panWithoutCard());
        Map<String, Object> body = transaction.build();

        given()
                .spec(Specs.logger())
                .body(body)
        .when()
                .post("/api/internal/log")
        .then()
                .statusCode(201);

        given()
                .spec(Specs.logger())
                .body(body)
        .when()
                .post("/api/internal/log")
        .then()
                .statusCode(200)
                .body("id", equalTo(transaction.id()))
                .body("stan", equalTo(transaction.stan()))
                .body("pan", equalTo(transaction.pan()))
                .body("status", equalTo("APPROVED"))
                .body("createdAt", notNullValue());
    }

    @Test
    @DisplayName("Совпадение id при расхождении данных: 409 Transaction conflict")
    void redeliveryDifferentDataReturns409() {
        TransactionLogBuilder transaction = TransactionLogBuilder.approved(TestData.panWithoutCard());
        Map<String, Object> body = transaction.build();

        given()
                .spec(Specs.logger())
                .body(body)
        .when()
                .post("/api/internal/log")
        .then()
                .statusCode(201);

        given()
                .spec(Specs.logger())
                .body(transaction.with("amount", 999999L).build())
        .when()
                .post("/api/internal/log")
        .then()
                .statusCode(409)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("error", equalTo("Transaction conflict"))
                .body("serviceName", equalTo("transaction-logger"))
                .body("message", containsString(transaction.id()));
    }

    static Stream<Arguments> invalidLogBodies() {
        return Stream.of(
                Arguments.of("stan не из 6 цифр",
                        TransactionLogBuilder.approved(TestData.panWithoutCard())
                                .with("stan", "12345").build()),
                Arguments.of("pan не из 16 цифр",
                        TransactionLogBuilder.approved("123").build()),
                Arguments.of("отсутствует id",
                        TransactionLogBuilder.approved(TestData.panWithoutCard())
                                .without("id").build()),
                Arguments.of("amount <= 0",
                        TransactionLogBuilder.approved(TestData.panWithoutCard())
                                .with("amount", 0L).build()),
                Arguments.of("неизвестный status",
                        TransactionLogBuilder.approved(TestData.panWithoutCard())
                                .with("status", "UNKNOWN").build())
        );
    }

    @ParameterizedTest(name = "{0} → 400")
    @MethodSource("invalidLogBodies")
    @DisplayName("Невалидное тело возвращает 400 с полем и нарушенным правилом в сообщении")
    void invalidBodyReturns400(String description, Map<String, Object> body) {
        given()
                .spec(Specs.logger())
                .body(body)
        .when()
                .post("/api/internal/log")
        .then()
                .statusCode(400)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("serviceName", equalTo("transaction-logger"));
    }
}
