package com.processing.apitests;

import com.processing.apitests.support.Specs;
import com.processing.apitests.support.TestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.isIn;
import static org.hamcrest.Matchers.matchesPattern;

/**
 * Контракт POST /api/cards/generate.
 * Источник ожиданий: docs/api-spec.md раздел 2, GenerateCardsRequest (common).
 */
class CardGenerateApiTest {

    @Test
    @DisplayName("POST /api/cards/generate создаёт запрошенное число карт: 201")
    void generateReturnsRequestedCount() {
        given()
                .spec(Specs.gateway())
                .body(Map.of("count", 5, "bins", List.of(TestData.KNOWN_BIN)))
        .when()
                .post("/api/cards/generate")
        .then()
                .statusCode(201)
                .body("generated", equalTo(5))
                .body("cards", hasSize(5))
                .body("cards.pan", everyItem(matchesPattern("\\d{16}")))
                .body("cards.bin", everyItem(equalTo(TestData.KNOWN_BIN)))
                // Расхождение со спецификацией: генератор распределяет статусы
                // ACTIVE 95% / INACTIVE 3% / BLOCKED 2% (CardGeneratorService),
                // spec не описывает не-ACTIVE карты. См. отчёт, раздел 4.
                .body("cards.status", everyItem(isIn(List.of("ACTIVE", "INACTIVE", "BLOCKED"))));
    }

    @Test
    @DisplayName("POST /api/cards/generate распределяет карты по запрошенным BIN")
    void generateDistributesAcrossBins() {
        List<String> bins = List.of(TestData.KNOWN_BIN, "400001");

        given()
                .spec(Specs.gateway())
                .body(Map.of("count", 4, "bins", bins))
        .when()
                .post("/api/cards/generate")
        .then()
                .statusCode(201)
                .body("generated", equalTo(4))
                .body("cards.bin", everyItem(isIn(bins)));
    }

    static Stream<Arguments> invalidGenerateBodies() {
        return Stream.of(
                Arguments.of("count=0", Map.of("count", 0, "bins", List.of(TestData.KNOWN_BIN))),
                Arguments.of("count=-1", Map.of("count", -1, "bins", List.of(TestData.KNOWN_BIN))),
                Arguments.of("bins=[]", Map.of("count", 1, "bins", List.of())),
                Arguments.of("bin не из 6 цифр", Map.of("count", 1, "bins", List.of("40000")))
        );
    }

    @ParameterizedTest(name = "{0} → 400")
    @MethodSource("invalidGenerateBodies")
    @DisplayName("POST /api/cards/generate с невалидным телом возвращает 400 card-management")
    void generateWithInvalidBodyReturns400(String description, Map<String, Object> body) {
        given()
                .spec(Specs.gateway())
                .body(body)
        .when()
                .post("/api/cards/generate")
        .then()
                .statusCode(400)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("serviceName", equalTo("card-management"));
    }
}
