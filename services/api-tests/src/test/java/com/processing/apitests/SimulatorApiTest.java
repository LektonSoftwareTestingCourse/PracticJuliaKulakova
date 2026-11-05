package com.processing.apitests;

import com.processing.apitests.support.Specs;
import com.processing.apitests.support.TestData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.oneOf;

/**
 * Контракты симуляторов: POST /api/simulator/terminal/run (terminal-simulator)
 * и POST /api/simulator/merchant/run (merchant-acquirer), оба через Gateway.
 * Источник ожиданий: docs/api-spec.md раздел 4, TerminalRunRequest/TerminalRunResponse,
 * SimulationController (merchant).
 */
class SimulatorApiTest {

    @BeforeAll
    static void ensureCardsExist() {
        given()
                .spec(Specs.gateway())
                .body(Map.of("count", 5, "bins", List.of(TestData.KNOWN_BIN)))
        .when()
                .post("/api/cards/generate")
        .then()
                .statusCode(201);
    }

    @Test
    @DisplayName("terminal/run возвращает статистику: totalSubmitted=count, approved+declined=count")
    void terminalRunReturnsStats() {
        given()
                .spec(Specs.gateway())
                .body(Map.of("count", 3, "scenario", "normal", "tps", 3))
        .when()
                .post("/api/simulator/terminal/run")
        .then()
                .statusCode(200)
                .body("totalSubmitted", equalTo(3))
                .body("approved", greaterThanOrEqualTo(0))
                .body("declined", greaterThanOrEqualTo(0))
                .body("elapsedMs", greaterThanOrEqualTo(0))
                .body("transactions", hasSize(3))
                .body("transactions.status", everyItem(oneOf("APPROVED", "DECLINED")));
    }

    @Test
    @DisplayName("terminal/run с неизвестным сценарием возвращает 400")
    void terminalRunUnknownScenarioReturns400() {
        given()
                .spec(Specs.gateway())
                .body(Map.of("count", 1, "scenario", "not_a_scenario", "tps", 1))
        .when()
                .post("/api/simulator/terminal/run")
        .then()
                .statusCode(400);
    }

    @Test
    @DisplayName("terminal/run с count=0 возвращает 400")
    void terminalRunZeroCountReturns400() {
        given()
                .spec(Specs.gateway())
                .body(Map.of("count", 0, "scenario", "normal", "tps", 1))
        .when()
                .post("/api/simulator/terminal/run")
        .then()
                .statusCode(400);
    }

    @Test
    @DisplayName("terminal/run без tps возвращает 400 (расхождение со спецификацией, см. отчёт раздел 4)")
    void terminalRunWithoutTpsReturns400() {
        given()
                .spec(Specs.gateway())
                .body(Map.of("count", 1, "scenario", "normal"))
        .when()
                .post("/api/simulator/terminal/run")
        .then()
                .statusCode(400);
    }

    @Test
    @DisplayName("merchant/run возвращает статистику симуляции")
    void merchantRunReturnsStats() {
        given()
                .spec(Specs.gateway())
                .body(Map.of("count", 2, "scenario", "grocery", "tps", 2))
        .when()
                .post("/api/simulator/merchant/run")
        .then()
                .statusCode(200)
                .body("totalSubmitted", equalTo(2))
                .body("transactions", hasSize(2));
    }

    @Test
    @DisplayName("merchant/run с неизвестным сценарием возвращает 400")
    void merchantRunUnknownScenarioReturns400() {
        given()
                .spec(Specs.gateway())
                .body(Map.of("count", 1, "scenario", "not_a_scenario"))
        .when()
                .post("/api/simulator/merchant/run")
        .then()
                .statusCode(400);
    }

    @Test
    @DisplayName("merchant/run с count=0 возвращает 400")
    void merchantRunZeroCountReturns400() {
        given()
                .spec(Specs.gateway())
                .body(Map.of("count", 0, "scenario", "grocery"))
        .when()
                .post("/api/simulator/merchant/run")
        .then()
                .statusCode(400);
    }
}
