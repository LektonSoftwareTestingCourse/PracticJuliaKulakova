package com.processing.apitests;

import com.processing.apitests.support.Specs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.oneOf;

/**
 * Контракт GET /health Gateway.
 * Источник ожиданий: docs/api-spec.md, раздел 1 Health.
 */
class GatewayHealthApiTest {

    @Test
    @DisplayName("GET /health возвращает 200 и агрегированный статус сервисов")
    void healthReturnsAggregatedStatus() {
        given()
                .spec(Specs.gateway())
        .when()
                .get("/health")
        .then()
                .statusCode(200)
                .body("status", equalTo("ok"))
                .body("service", equalTo("gateway"))
                .body("version", equalTo("1.0.0"))
                .body("services", hasKey("switch"))
                .body("services", hasKey("authorization"))
                .body("services", hasKey("cardManagement"))
                .body("services", hasKey("logger"))
                .body("services.switch", oneOf("ok", "degraded", "unavailable"));
    }

    @Test
    @DisplayName("GET /health возвращает JSON Content-Type")
    void healthReturnsJsonContentType() {
        given()
                .spec(Specs.gateway())
        .when()
                .get("/health")
        .then()
                .statusCode(200)
                .contentType("application/json");
    }
}
