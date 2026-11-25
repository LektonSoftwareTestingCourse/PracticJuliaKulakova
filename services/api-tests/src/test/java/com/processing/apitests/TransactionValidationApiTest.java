package com.processing.apitests;

import com.processing.apitests.support.AuthRequestBuilder;
import com.processing.apitests.support.CardApi;
import com.processing.apitests.support.Specs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.restassured.RestAssured.given;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * Валидация запроса транзакции выполняется самим Gateway до маршрутизации.
 * Источник ожиданий: services/gateway/.../validation/TransactionRequestValidator.java
 * и TransactionValidationFilter (тело ошибки: error=VALIDATION_ERROR, serviceName=gateway).
 * Каждый тест проверяет ровно один класс нарушения, чтобы локализовать причину отказа.
 */
class TransactionValidationApiTest {

    @ParameterizedTest(name = "отсутствует обязательное поле {0} → 400")
    @ValueSource(strings = {
            "mti", "stan", "pan", "processingCode", "currencyCode",
            "transmissionDateTime", "terminalId", "merchantId", "mcc", "acquirerId"
    })
    @DisplayName("Отсутствие обязательного поля возвращает 400 с именем поля в сообщении")
    void missingRequiredFieldReturns400(String field) {
        given()
                .spec(Specs.gateway())
                .body(AuthRequestBuilder.valid(CardApi.createActiveCard()).without(field).build())
        .when()
                .post("/api/transactions")
        .then()
                .statusCode(400)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("error", equalTo("VALIDATION_ERROR"))
                .body("serviceName", equalTo("gateway"))
                .body("message", equalTo("Field '" + field + "' is required"));
    }

    @Test
    @DisplayName("Отсутствующее поле amount возвращает 400 с отдельным сообщением")
    void missingAmountReturns400() {
        given()
                .spec(Specs.gateway())
                .body(AuthRequestBuilder.valid(CardApi.createActiveCard()).without("amount").build())
        .when()
                .post("/api/transactions")
        .then()
                .statusCode(400)
                .body("error", equalTo("VALIDATION_ERROR"))
                .body("serviceName", equalTo("gateway"))
                .body("message", equalTo("Field 'amount' is required"));
    }

    @Test
    @DisplayName("mti не равен 0100 → 400 с текстом правила")
    void invalidMtiReturns400() {
        given()
                .spec(Specs.gateway())
                .body(AuthRequestBuilder.valid(CardApi.createActiveCard()).with("mti", "0200").build())
        .when()
                .post("/api/transactions")
        .then()
                .statusCode(400)
                .body("error", equalTo("VALIDATION_ERROR"))
                .body("serviceName", equalTo("gateway"))
                .body("message", equalTo("Field 'mti' must be '0100'"));
    }

    @ParameterizedTest(name = "pan={0} → 400")
    @ValueSource(strings = {"400000123456789", "40000012345678901", "40000012345678ab"})
    @DisplayName("PAN не из 16 цифр возвращает 400 (границы длины: 15 и 17, недопустимые символы)")
    void invalidPanReturns400(String pan) {
        given()
                .spec(Specs.gateway())
                .body(AuthRequestBuilder.valid(pan).build())
        .when()
                .post("/api/transactions")
        .then()
                .statusCode(400)
                .body("error", equalTo("VALIDATION_ERROR"))
                .body("serviceName", equalTo("gateway"))
                .body("message", equalTo("Field 'pan' must be exactly 16 digits"));
    }

    @ParameterizedTest(name = "currencyCode={0} → 400")
    @ValueSource(strings = {"64", "6431"})
    @DisplayName("currencyCode не из 3 символов возвращает 400 (границы длины 2 и 4)")
    void invalidCurrencyCodeReturns400(String currencyCode) {
        given()
                .spec(Specs.gateway())
                .body(AuthRequestBuilder.valid(CardApi.createActiveCard())
                        .with("currencyCode", currencyCode)
                        .build())
        .when()
                .post("/api/transactions")
        .then()
                .statusCode(400)
                .body("error", equalTo("VALIDATION_ERROR"))
                .body("serviceName", equalTo("gateway"))
                .body("message", equalTo("Field 'currencyCode' must be exactly 3 characters"));
    }

    @ParameterizedTest(name = "mcc={0} → 400")
    @ValueSource(strings = {"541", "54111", "54ab"})
    @DisplayName("mcc не из 4 цифр возвращает 400 (границы длины и недопустимые символы)")
    void invalidMccReturns400(String mcc) {
        given()
                .spec(Specs.gateway())
                .body(AuthRequestBuilder.valid(CardApi.createActiveCard()).with("mcc", mcc).build())
        .when()
                .post("/api/transactions")
        .then()
                .statusCode(400)
                .body("error", equalTo("VALIDATION_ERROR"))
                .body("serviceName", equalTo("gateway"))
                .body("message", equalTo("Field 'mcc' must be exactly 4 digits"));
    }

    @Test
    @DisplayName("Невалидный JSON возвращает 400 с сообщением о формате тела")
    void malformedJsonReturns400() {
        given()
                .spec(Specs.gateway())
                .body("{not-a-json")
        .when()
                .post("/api/transactions")
        .then()
                .statusCode(400)
                .body("error", equalTo("VALIDATION_ERROR"))
                .body("serviceName", equalTo("gateway"))
                .body("message", equalTo("Request body must be valid JSON"));
    }

    @Test
    @DisplayName("Пустое тело возвращает 400 с сообщением о формате тела")
    void emptyBodyReturns400() {
        given()
                .spec(Specs.gateway())
                .body("")
        .when()
                .post("/api/transactions")
        .then()
                .statusCode(400)
                .body("error", equalTo("VALIDATION_ERROR"))
                .body("serviceName", equalTo("gateway"))
                .body("message", equalTo("Request body must be valid JSON"));
    }

    @Test
    @DisplayName("transmissionDateTime не в ISO-8601 возвращает 400 (ошибка разбора JSON)")
    void invalidDateTimeFormatReturns400() {
        given()
                .spec(Specs.gateway())
                .body(AuthRequestBuilder.valid(CardApi.createActiveCard())
                        .with("transmissionDateTime", "01-06-2026 10:30")
                        .build())
        .when()
                .post("/api/transactions")
        .then()
                .statusCode(400)
                .body("error", equalTo("VALIDATION_ERROR"))
                .body("message", containsString("JSON"));
    }
}
