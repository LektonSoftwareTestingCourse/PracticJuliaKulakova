package com.processing.apitests;

import com.processing.apitests.support.SearchApi;
import com.processing.apitests.support.Specs;
import com.processing.apitests.support.TestData;
import com.processing.apitests.support.TransactionLogBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;

/**
 * Контракт GET /api/transactions/search (transaction-logger через Gateway).
 * Источник ожиданий: docs/api-spec.md раздел 3, TransactionFilter и
 * TransactionSpecification (фильтры по transmissionDateTime, полнотекст declineReason).
 * Данные готовятся через API transaction-logger (POST /api/internal/log).
 */
class TransactionSearchApiTest {

    @Test
    @DisplayName("Поиск по PAN возвращает только транзакции этой карты (GPath-фильтр)")
    void searchByPanReturnsOwnTransactions() {
        String targetPan = TestData.panWithoutCard();
        String otherPan = TestData.panWithoutCard();
        TransactionLogBuilder target = TransactionLogBuilder.approved(targetPan);
        SearchApi.storeTransaction(target.build()).then().statusCode(201);
        SearchApi.storeTransaction(TransactionLogBuilder.approved(otherPan).build())
                .then().statusCode(201);

        SearchApi.searchByPan(targetPan)
                .then()
                .statusCode(200)
                .body(matchesJsonSchemaInClasspath("schemas/transaction-search.json"))
                .body("total", equalTo(1))
                .body("transactions", hasSize(1))
                .body("transactions.findAll { it.pan == '" + targetPan + "' }", hasSize(1))
                .body("transactions[0].stan", equalTo(target.stan()))
                .body("transactions[0].status", equalTo("APPROVED"))
                .body("transactions[0].rrn", equalTo(target.build().get("rrn")));
    }

    @Test
    @DisplayName("Комбинация pan+status (попарное покрытие): APPROVED и DECLINED разделяются")
    void searchByPanAndStatusCombination() {
        String pan = TestData.panWithoutCard();
        SearchApi.storeTransaction(TransactionLogBuilder.approved(pan).build())
                .then().statusCode(201);
        SearchApi.storeTransaction(
                        TransactionLogBuilder.declined(pan, "INSUFFICIENT_FUNDS").build())
                .then().statusCode(201);

        SearchApi.search(Map.of("pan", pan, "status", "APPROVED"))
                .then()
                .statusCode(200)
                .body("total", equalTo(1))
                .body("transactions.status", everyItem(equalTo("APPROVED")));

        SearchApi.search(Map.of("pan", pan, "status", "DECLINED"))
                .then()
                .statusCode(200)
                .body("total", equalTo(1))
                .body("transactions.status", everyItem(equalTo("DECLINED")))
                .body("transactions[0].declineReason", equalTo("INSUFFICIENT_FUNDS"));
    }

    @Test
    @DisplayName("Поиск по merchantId возвращает сохранённую транзакцию")
    void searchByMerchantId() {
        TransactionLogBuilder stored = TransactionLogBuilder.approved(TestData.panWithoutCard());
        SearchApi.storeTransaction(stored.build()).then().statusCode(201);

        SearchApi.search(Map.of("merchantId", stored.merchantId()))
                .then()
                .statusCode(200)
                .body("total", equalTo(1))
                .body("transactions[0].merchantId", equalTo(stored.merchantId()));
    }

    @Test
    @DisplayName("Полнотекстовый поиск по declineReason находит подстроку причины")
    void searchByDeclineReasonFullText() {
        String pan = TestData.panWithoutCard();
        SearchApi.storeTransaction(TransactionLogBuilder.declined(pan, "INSUFFICIENT_FUNDS").build())
                .then().statusCode(201);

        SearchApi.search(Map.of("pan", pan, "declineReason", "INSUFFICIENT"))
                .then()
                .statusCode(200)
                .body("total", equalTo(1))
                .body("transactions[0].declineReason", equalTo("INSUFFICIENT_FUNDS"));
    }

    @Test
    @DisplayName("Фильтр по диапазону дат включает транзакцию сегодняшнего дня")
    void searchByDateRange() {
        String pan = TestData.panWithoutCard();
        SearchApi.storeTransaction(TransactionLogBuilder.approved(pan).build())
                .then().statusCode(201);
        LocalDate today = LocalDate.now();

        SearchApi.search(Map.of("pan", pan,
                        "dateFrom", today.toString(),
                        "dateTo", today.plusDays(1).toString()))
                .then()
                .statusCode(200)
                .body("total", equalTo(1));

        SearchApi.search(Map.of("pan", pan,
                        "dateFrom", today.minusDays(10).toString(),
                        "dateTo", today.minusDays(5).toString()))
                .then()
                .statusCode(200)
                .body("total", equalTo(0))
                .body("transactions", hasSize(0));
    }

    @Test
    @DisplayName("Пагинация limit/offset: размер страницы и общий счётчик")
    void searchPagination() {
        String merchantId = TestData.nextMerchantId();
        String pan = TestData.panWithoutCard();
        for (int i = 0; i < 3; i++) {
            SearchApi.storeTransaction(TransactionLogBuilder.approved(pan)
                            .with("merchantId", merchantId)
                            .build())
                    .then().statusCode(201);
        }

        SearchApi.search(Map.of("merchantId", merchantId, "limit", 2, "offset", 0))
                .then()
                .statusCode(200)
                .body("total", equalTo(3))
                .body("transactions", hasSize(2));

        SearchApi.search(Map.of("merchantId", merchantId, "limit", 2, "offset", 2))
                .then()
                .statusCode(200)
                .body("total", equalTo(3))
                .body("transactions", hasSize(1));
    }

    @Test
    @DisplayName("Пустая выборка: total=0 и пустой массив для несуществующего PAN")
    void searchEmptyResult() {
        SearchApi.searchByPan(TestData.panWithoutCard())
                .then()
                .statusCode(200)
                .body(matchesJsonSchemaInClasspath("schemas/transaction-search.json"))
                .body("total", equalTo(0))
                .body("transactions", hasSize(0));
    }

    @ParameterizedTest(name = "limit={0} → 400")
    @ValueSource(strings = {"0", "-1", "501"})
    @DisplayName("Недопустимый limit (границы 1..500) возвращает 400 с указанием поля")
    void searchInvalidLimitReturns400(String limit) {
        given()
                .spec(Specs.gateway())
                .queryParam("limit", limit)
        .when()
                .get("/api/transactions/search")
        .then()
                .statusCode(400)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("serviceName", equalTo("transaction-logger"))
                .body("message", containsString("limit"));
    }

    @Test
    @DisplayName("Отрицательный offset возвращает 400 с указанием поля")
    void searchInvalidOffsetReturns400() {
        given()
                .spec(Specs.gateway())
                .queryParam("offset", -1)
        .when()
                .get("/api/transactions/search")
        .then()
                .statusCode(400)
                .body("serviceName", equalTo("transaction-logger"))
                .body("message", containsString("offset"));
    }

    @Test
    @DisplayName("dateFrom позже dateTo возвращает 400 (валидация диапазона)")
    void searchInvalidDateRangeReturns400() {
        LocalDate today = LocalDate.now();

        given()
                .spec(Specs.gateway())
                .queryParam("dateFrom", today.plusDays(1).toString())
                .queryParam("dateTo", today.minusDays(1).toString())
        .when()
                .get("/api/transactions/search")
        .then()
                .statusCode(400)
                .body("serviceName", equalTo("transaction-logger"));
    }

    @Test
    @DisplayName("Некорректное значение status возвращает 400")
    void searchInvalidStatusReturns400() {
        given()
                .spec(Specs.gateway())
                .queryParam("status", "NOT_A_STATUS")
        .when()
                .get("/api/transactions/search")
        .then()
                .statusCode(400);
    }

    @Test
    @DisplayName("Экспорт в CSV: text/csv, Content-Disposition и stan транзакции в теле")
    void exportReturnsCsvWithTransaction() {
        TransactionLogBuilder stored = TransactionLogBuilder.approved(TestData.panWithoutCard());
        SearchApi.storeTransaction(stored.build()).then().statusCode(201);

        given()
                .spec(Specs.gateway())
                .queryParam("merchantId", stored.merchantId())
        .when()
                .get("/api/transactions/export")
        .then()
                .statusCode(200)
                .contentType(containsString("text/csv"))
                .header("Content-Disposition", containsString("transactions.csv"))
                .body(containsString(stored.stan()));
    }
}
