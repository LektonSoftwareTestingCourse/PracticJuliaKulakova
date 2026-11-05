package com.processing.apitests.support;

import io.restassured.response.Response;

import java.util.LinkedHashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Подготовка состояния системы через HTTP API card-management
 * (без прямого доступа к БД): создание карт с известным балансом и лимитами,
 * смена статуса, чтение баланса.
 */
public final class CardApi {

    public static final long DEFAULT_BALANCE = 10_000_000L;
    public static final long DEFAULT_DAILY_LIMIT = 1_000_000L;
    public static final long DEFAULT_MONTHLY_LIMIT = 10_000_000L;

    private CardApi() {
    }

    public static Map<String, Object> validCreateBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bin", TestData.KNOWN_BIN);
        body.put("cardholderName", TestData.uniqueHolderName());
        body.put("currencyCode", "643");
        body.put("dailyLimit", DEFAULT_DAILY_LIMIT);
        body.put("monthlyLimit", DEFAULT_MONTHLY_LIMIT);
        body.put("initialBalance", DEFAULT_BALANCE);
        return body;
    }

    public static Response createCard(Map<String, Object> body) {
        return given()
                .spec(Specs.cardManagement())
                .body(body)
                .when()
                .post("/api/cards");
    }

    public static String createActiveCard(String bin, long balance, long dailyLimit, long monthlyLimit) {
        Map<String, Object> body = validCreateBody();
        body.put("bin", bin);
        body.put("initialBalance", balance);
        body.put("dailyLimit", dailyLimit);
        body.put("monthlyLimit", monthlyLimit);
        Response response = createCard(body);
        response.then().statusCode(201);
        return response.jsonPath().getString("pan");
    }

    public static String createActiveCard() {
        return createActiveCard(TestData.KNOWN_BIN, DEFAULT_BALANCE, DEFAULT_DAILY_LIMIT, DEFAULT_MONTHLY_LIMIT);
    }

    public static Response getCard(String pan) {
        return given()
                .spec(Specs.cardManagement())
                .when()
                .get("/api/cards/{pan}", pan);
    }

    public static Response patchCard(String pan, Map<String, Object> body) {
        return given()
                .spec(Specs.cardManagement())
                .body(body)
                .when()
                .patch("/api/cards/{pan}", pan);
    }

    public static void changeStatus(String pan, String status) {
        patchCard(pan, Map.of("status", status))
                .then()
                .statusCode(200)
                .body("status", equalTo(status));
    }

    public static long availableBalance(String pan) {
        return getCard(pan)
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getLong("availableBalance");
    }

    /**
     * Баланс карты без HTTP-assert'ов; {@code -1} при недоступности карты.
     * Пригоден для условий Awaitility, где исключения нежелательны.
     */
    public static long balanceQuietly(String pan) {
        Response response = getCard(pan);
        if (response.getStatusCode() != 200) {
            return -1L;
        }
        return response.jsonPath().getLong("availableBalance");
    }
}
