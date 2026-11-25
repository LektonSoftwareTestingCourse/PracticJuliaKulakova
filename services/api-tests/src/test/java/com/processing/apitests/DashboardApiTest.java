package com.processing.apitests;

import com.processing.apitests.support.SearchApi;
import com.processing.apitests.support.Specs;
import com.processing.apitests.support.TestData;
import com.processing.apitests.support.TransactionLogBuilder;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

/**
 * Контракты Dashboard API (transaction-logger через Gateway).
 * Источник ожиданий: docs/api-spec.md раздел 5, DashboardController,
 * DashboardStatsResponse.
 */
class DashboardApiTest {

    @Test
    @DisplayName("GET /api/dashboard/stats возвращает агрегированную статистику по контракту")
    void statsReturnsAggregatedData() {
        given()
                .spec(Specs.gateway())
        .when()
                .get("/api/dashboard/stats")
        .then()
                .statusCode(200)
                .body("totalTransactions", greaterThanOrEqualTo(0))
                .body("approvedCount", greaterThanOrEqualTo(0))
                .body("declinedCount", greaterThanOrEqualTo(0))
                .body("approvalRate", greaterThanOrEqualTo(0.0f))
                .body("approvalRate", lessThanOrEqualTo(1.0f))
                .body("totalAmount", not(nullValue()))
                .body("averageAmount", not(nullValue()))
                .body("avgProcessingTimeMs", not(nullValue()))
                .body("transactionsPerMinute", greaterThanOrEqualTo(0));
    }

    @Test
    @DisplayName("Статистика внутренне согласована: approved + declined = total")
    void statsIsInternallyConsistent() {
        SearchApi.storeTransaction(TransactionLogBuilder.approved(TestData.panWithoutCard()).build())
                .then().statusCode(201);
        SearchApi.storeTransaction(
                        TransactionLogBuilder.declined(TestData.panWithoutCard(), "CARD_BLOCKED").build())
                .then().statusCode(201);

        JsonPath stats = given()
                .spec(Specs.gateway())
        .when()
                .get("/api/dashboard/stats")
        .then()
                .statusCode(200)
                .extract().jsonPath();

        long total = stats.getLong("totalTransactions");
        long approved = stats.getLong("approvedCount");
        long declined = stats.getLong("declinedCount");
        Assertions.assertEquals(total, approved + declined,
                "approvedCount + declinedCount должно равняться totalTransactions");
        Assertions.assertTrue(total >= 2, "сохранённые транзакции должны учитываться в статистике");
    }

    @Test
    @DisplayName("GET /api/dashboard/recent?limit=N возвращает не более N записей с заполненным createdAt")
    void recentRespectsLimit() {
        SearchApi.storeTransaction(TransactionLogBuilder.approved(TestData.panWithoutCard()).build())
                .then().statusCode(201);

        given()
                .spec(Specs.gateway())
                .queryParam("limit", 5)
        .when()
                .get("/api/dashboard/recent")
        .then()
                .statusCode(200)
                .body("$", hasSize(lessThanOrEqualTo(5)))
                .body("createdAt", everyItem(not(nullValue())));
    }

    @Test
    @DisplayName("recent отсортирован по createdAt по убыванию")
    void recentIsSortedByCreatedAtDesc() {
        SearchApi.storeTransaction(TransactionLogBuilder.approved(TestData.panWithoutCard()).build())
                .then().statusCode(201);

        List<String> dates = given()
                .spec(Specs.gateway())
                .queryParam("limit", 10)
        .when()
                .get("/api/dashboard/recent")
        .then()
                .statusCode(200)
                .extract().jsonPath().getList("createdAt");

        List<String> sorted = dates.stream().sorted(Comparator.reverseOrder()).toList();
        Assertions.assertEquals(sorted, dates, "recent должен быть отсортирован по createdAt DESC");
    }

    @ParameterizedTest(name = "limit={0} → 400")
    @ValueSource(strings = {"0", "-1", "501"})
    @DisplayName("Недопустимый limit в recent (границы 1..500) возвращает 400")
    void recentInvalidLimitReturns400(String limit) {
        given()
                .spec(Specs.gateway())
                .queryParam("limit", limit)
        .when()
                .get("/api/dashboard/recent")
        .then()
                .statusCode(400)
                .body("message", containsString("limit"));
    }

    @Test
    @DisplayName("GET /api/dashboard/charts?granularity=hour возвращает временные корзины")
    void chartsHourGranularityReturnsBuckets() {
        SearchApi.storeTransaction(TransactionLogBuilder.approved(TestData.panWithoutCard()).build())
                .then().statusCode(201);

        given()
                .spec(Specs.gateway())
                .queryParam("granularity", "hour")
        .when()
                .get("/api/dashboard/charts")
        .then()
                .statusCode(200)
                .body("$", hasSize(greaterThanOrEqualTo(1)));
    }

    @Test
    @DisplayName("GET /api/dashboard/charts с недопустимой гранулярностью возвращает 400")
    void chartsInvalidGranularityReturns400() {
        given()
                .spec(Specs.gateway())
                .queryParam("granularity", "week")
        .when()
                .get("/api/dashboard/charts")
        .then()
                .statusCode(400)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("message", containsString("granularity"));
    }

    @Test
    @DisplayName("GET /api/dashboard/charts с from позже to возвращает 400")
    void chartsInvalidRangeReturns400() {
        given()
                .spec(Specs.gateway())
                .queryParams(Map.of(
                        "from", "2026-06-10T00:00:00Z",
                        "to", "2026-06-01T00:00:00Z"))
        .when()
                .get("/api/dashboard/charts")
        .then()
                .statusCode(400);
    }
}
