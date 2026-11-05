package com.processing.apitests;

import com.processing.apitests.support.AuthRequestBuilder;
import com.processing.apitests.support.CardApi;
import com.processing.apitests.support.SearchApi;
import com.processing.apitests.support.Specs;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

/**
 * Сквозная цепочка: Gateway → switch → authorization → RabbitMQ → transaction-logger.
 * Транзакция, отправленная через Gateway, должна достичь хранилища и стать
 * доступной для поиска с теми же доменными значениями (rrn, authCode, issuerId).
 */
class EndToEndChainApiTest {

    private static final Duration APPEAR_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);

    @Test
    @DisplayName("Транзакция через Gateway достигает хранилища и находится поиском с теми же rrn/authCode")
    void transactionReachesStorageAndIsSearchable() {
        String pan = CardApi.createActiveCard();
        AuthRequestBuilder request = AuthRequestBuilder.valid(pan).with("amount", 123456L);

        JsonPath authResponse = given()
                .spec(Specs.gateway())
                .body(request.build())
        .when()
                .post("/api/transactions")
        .then()
                .statusCode(200)
                .body("status", equalTo("APPROVED"))
                .extract().jsonPath();

        String rrn = authResponse.getString("rrn");
        String authCode = authResponse.getString("authCode");

        await("транзакция должна стать доступной для поиска")
                .atMost(APPEAR_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .untilAsserted(() ->
                        SearchApi.searchByPan(pan)
                                .then()
                                .statusCode(200)
                                .body("total", equalTo(1))
                                .body("transactions", hasSize(1))
                                .body("transactions[0].stan", equalTo(request.stan()))
                                .body("transactions[0].pan", equalTo(pan))
                                .body("transactions[0].amount", equalTo(123456))
                                .body("transactions[0].status", equalTo("APPROVED"))
                                .body("transactions[0].rrn", equalTo(rrn))
                                .body("transactions[0].authCode", equalTo(authCode))
                                .body("transactions[0].issuerId", equalTo("ISS001"))
                                .body("transactions[0].declineReason", nullValue()));
    }

    @Test
    @DisplayName("Сохранённая транзакция попадает в CSV-экспорт")
    void storedTransactionAppearsInCsvExport() {
        String pan = CardApi.createActiveCard();
        AuthRequestBuilder request = AuthRequestBuilder.valid(pan);

        given()
                .spec(Specs.gateway())
                .body(request.build())
        .when()
                .post("/api/transactions")
        .then()
                .statusCode(200)
                .body("status", equalTo("APPROVED"));

        await("транзакция должна появиться в хранилище")
                .atMost(APPEAR_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .untilAsserted(() ->
                        SearchApi.searchByPan(pan)
                                .then().statusCode(200).body("total", equalTo(1)));

        given()
                .spec(Specs.gateway())
                .queryParam("pan", pan)
        .when()
                .get("/api/transactions/export")
        .then()
                .statusCode(200)
                .body(containsString(request.stan()));
    }

    @Test
    @DisplayName("Сохранённая транзакция видна в dashboard/recent")
    void storedTransactionAppearsInDashboardRecent() {
        String pan = CardApi.createActiveCard();
        AuthRequestBuilder request = AuthRequestBuilder.valid(pan);

        given()
                .spec(Specs.gateway())
                .body(request.build())
        .when()
                .post("/api/transactions")
        .then()
                .statusCode(200)
                .body("status", equalTo("APPROVED"));

        await("транзакция должна появиться в recent")
                .atMost(APPEAR_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .untilAsserted(() ->
                        given()
                                .spec(Specs.gateway())
                                .queryParam("limit", 500)
                        .when()
                                .get("/api/dashboard/recent")
                        .then()
                                .statusCode(200)
                                .body("findAll { it.stan == '" + request.stan() + "' }", hasSize(1)));
    }
}
