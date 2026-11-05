package com.processing.apitests.support;

import io.restassured.response.Response;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;

/**
 * Вспомогательные запросы поиска транзакций через Gateway
 * и сохранения транзакций напрямую в transaction-logger.
 */
public final class SearchApi {

    private SearchApi() {
    }

    public static Response search(Map<String, ?> queryParams) {
        return given()
                .spec(Specs.gateway())
                .queryParams(queryParams)
                .when()
                .get("/api/transactions/search");
    }

    public static Response searchByPan(String pan) {
        return search(Map.of("pan", pan));
    }

    public static int totalByPan(String pan) {
        return searchByPan(pan).then().statusCode(200).extract().jsonPath().getInt("total");
    }

    /**
     * Признак отсутствия транзакций с данным PAN в хранилище.
     * Не бросает исключений — пригоден для условия Awaitility.
     */
    public static boolean isAbsent(String pan) {
        Response response = searchByPan(pan);
        return response.getStatusCode() == 200 && response.jsonPath().getInt("total") == 0;
    }

    public static List<Map<String, Object>> findByStan(String pan, String stan) {
        return searchByPan(pan)
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("transactions.findAll { it.stan == '" + stan + "' }");
    }

    /** Сохраняет транзакцию напрямую в transaction-logger (POST /api/internal/log → 201). */
    public static Response storeTransaction(Map<String, Object> transactionBody) {
        return given()
                .spec(Specs.logger())
                .body(transactionBody)
                .when()
                .post("/api/internal/log");
    }

    /** Создаёт и синхронно сохраняет транзакцию, возвращает её тело. */
    public static Map<String, Object> storeApprovedTransaction(String pan) {
        TransactionLogBuilder builder = TransactionLogBuilder.approved(pan);
        storeTransaction(builder.build()).then().statusCode(201);
        return builder.build();
    }
}
