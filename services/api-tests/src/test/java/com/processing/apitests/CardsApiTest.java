package com.processing.apitests;

import com.processing.apitests.support.CardApi;
import com.processing.apitests.support.Specs;
import com.processing.apitests.support.TestData;
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
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Контракты эндпоинтов /api/cards (card-management через Gateway).
 * Источники ожиданий: docs/api-spec.md раздел 2 Cards,
 * services/card-management/.../controllers/CardController.java,
 * маршруты Gateway (services/gateway/src/main/resources/application.yml).
 */
class CardsApiTest {

    @Test
    @DisplayName("POST /api/cards создаёт карту: 201 и полное тело по контракту Card")
    void createCardReturns201WithContractBody() {
        Map<String, Object> body = CardApi.validCreateBody();

        given()
                .spec(Specs.gateway())
                .body(body)
        .when()
                .post("/api/cards")
        .then()
                .statusCode(201)
                .body(matchesJsonSchemaInClasspath("schemas/card.json"))
                .body("id", notNullValue())
                .body("pan", matchesPattern("\\d{16}"))
                .body("bin", equalTo(body.get("bin")))
                .body("cardholderName", equalTo(body.get("cardholderName")))
                .body("status", equalTo("ACTIVE"))
                .body("currencyCode", equalTo("643"))
                .body("dailyLimit", notNullValue())
                .body("monthlyLimit", notNullValue())
                .body("availableBalance", notNullValue())
                .body("createdAt", notNullValue());
    }

    // Ожидаемые тексты — сообщения валидаторов common-аннотаций
    // (@Bin, @ExactSize, @NotNegative, @NotBlank, паттерн cardholderName).
    // Расхождение: тело ошибки card-management не содержит имени поля,
    // только текст правила (GlobalExceptionHandler). См. отчёт, раздел 4.
    static Stream<Arguments> invalidCreateBodies() {
        return Stream.of(
                Arguments.of("bin", mutate("bin", "40000"), "BIN"),
                Arguments.of("bin", mutate("bin", "abcdef"), "BIN"),
                Arguments.of("cardholderName", mutate("cardholderName", "ivan ivanov"), "uppercase"),
                Arguments.of("currencyCode", mutate("currencyCode", "64"), "exactly 3"),
                Arguments.of("currencyCode", mutate("currencyCode", "6431"), "exactly 3"),
                Arguments.of("dailyLimit", mutate("dailyLimit", -1), "negative"),
                Arguments.of("monthlyLimit", mutate("monthlyLimit", -1), "negative"),
                Arguments.of("bin", removed("bin"), "blank"),
                Arguments.of("cardholderName", removed("cardholderName"), "blank")
        );
    }

    @ParameterizedTest(name = "невалидное поле {0} → 400")
    @MethodSource("invalidCreateBodies")
    @DisplayName("POST /api/cards с одним невалидным полем возвращает 400 и имя нарушенного правила")
    void createCardWithInvalidFieldReturns400(String field, Map<String, Object> body, String expectedInMessage) {
        given()
                .spec(Specs.gateway())
                .body(body)
        .when()
                .post("/api/cards")
        .then()
                .statusCode(400)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("serviceName", equalTo("card-management"))
                .body("message", containsString(expectedInMessage));
    }

    @Test
    @DisplayName("GET /api/cards/{pan} возвращает 200 и данные созданной карты")
    void getCardByPanReturns200() {
        String pan = CardApi.createActiveCard();

        given()
                .spec(Specs.gateway())
        .when()
                .get("/api/cards/{pan}", pan)
        .then()
                .statusCode(200)
                .body(matchesJsonSchemaInClasspath("schemas/card.json"))
                .body("pan", equalTo(pan))
                .body("bin", equalTo(TestData.KNOWN_BIN))
                .body("status", equalTo("ACTIVE"))
                .body("availableBalance", notNullValue());
    }

    @Test
    @DisplayName("GET /api/cards/{pan} для несуществующей карты возвращает 404 card-management")
    void getUnknownCardReturns404() {
        String pan = TestData.panWithoutCard();

        given()
                .spec(Specs.gateway())
        .when()
                .get("/api/cards/{pan}", pan)
        .then()
                .statusCode(404)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("serviceName", equalTo("card-management"))
                .body("error", equalTo("CardNotFoundException"));
    }

    @Test
    @DisplayName("GET /api/cards/{pan} с PAN не из 16 цифр возвращает 400")
    void getCardWithInvalidPanReturns400() {
        given()
                .spec(Specs.gateway())
        .when()
                .get("/api/cards/{pan}", "12345")
        .then()
                .statusCode(400)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("serviceName", equalTo("card-management"))
                .body("message", containsString("16 digits"));
    }

    @Test
    @DisplayName("GET /api/cards с limit/offset возвращает страницу не больше limit")
    void getCardsRespectsPagination() {
        CardApi.createActiveCard();

        given()
                .spec(Specs.gateway())
                .queryParam("limit", 2)
                .queryParam("offset", 0)
        .when()
                .get("/api/cards")
        .then()
                .statusCode(200)
                .body("total", greaterThanOrEqualTo(1))
                .body("cards", hasSize(lessThanOrEqualTo(2)));
    }

    @Test
    @DisplayName("GET /api/cards с фильтром status=BLOCKED возвращает только заблокированные карты")
    void getCardsFiltersByStatus() {
        String pan = CardApi.createActiveCard();
        CardApi.changeStatus(pan, "BLOCKED");

        given()
                .spec(Specs.gateway())
                .queryParam("status", "BLOCKED")
                .queryParam("bin", TestData.KNOWN_BIN)
                .queryParam("limit", 500)
        .when()
                .get("/api/cards")
        .then()
                .statusCode(200)
                .body("cards.findAll { it.pan == '" + pan + "' }", hasSize(1))
                .body("cards.status.unique()", org.hamcrest.Matchers.everyItem(equalTo("BLOCKED")));
    }

    @Test
    @DisplayName("GET /api/cards с недостижимым фильтром возвращает пустую выборку")
    void getCardsEmptySelection() {
        given()
                .spec(Specs.gateway())
                .queryParam("issuerId", "ZZZZZZZZZZ")
        .when()
                .get("/api/cards")
        .then()
                .statusCode(200)
                .body("total", equalTo(0))
                .body("cards", hasSize(0));
    }

    @Test
    @DisplayName("GET /api/cards с невалидным limit возвращает 400")
    void getCardsInvalidLimitReturns400() {
        given()
                .spec(Specs.gateway())
                .queryParam("limit", 0)
        .when()
                .get("/api/cards")
        .then()
                .statusCode(400)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("serviceName", equalTo("card-management"));
    }

    @Test
    @DisplayName("PATCH /api/cards/{pan} меняет статус карты: 200 и новый статус")
    void patchCardChangesStatus() {
        String pan = CardApi.createActiveCard();

        given()
                .spec(Specs.gateway())
                .body(Map.of("status", "INACTIVE"))
        .when()
                .patch("/api/cards/{pan}", pan)
        .then()
                .statusCode(200)
                .body("pan", equalTo(pan))
                .body("status", equalTo("INACTIVE"));
    }

    @Test
    @DisplayName("PATCH /api/cards/{pan} для несуществующей карты возвращает 404")
    void patchUnknownCardReturns404() {
        given()
                .spec(Specs.gateway())
                .body(Map.of("status", "INACTIVE"))
        .when()
                .patch("/api/cards/{pan}", TestData.panWithoutCard())
        .then()
                .statusCode(404)
                .body("serviceName", equalTo("card-management"));
    }

    @Test
    @DisplayName("DELETE /api/cards/{pan} возвращает 204, карта перестаёт находиться")
    void deleteCardReturns204() {
        String pan = CardApi.createActiveCard();

        given()
                .spec(Specs.gateway())
        .when()
                .delete("/api/cards/{pan}", pan)
        .then()
                .statusCode(204);

        CardApi.getCard(pan)
                .then()
                .statusCode(404);
    }

    @Test
    @DisplayName("GET /api/cards/{pan}: первый запрос X-Cache=MISS, повторный X-Cache=HIT (кэш Gateway)")
    void gatewayCachesCardResponses() {
        String pan = CardApi.createActiveCard();

        given()
                .spec(Specs.gateway())
        .when()
                .get("/api/cards/{pan}", pan)
        .then()
                .statusCode(200)
                .header("X-Cache", "MISS");

        given()
                .spec(Specs.gateway())
        .when()
                .get("/api/cards/{pan}", pan)
        .then()
                .statusCode(200)
                .header("X-Cache", "HIT")
                .contentType("application/json")
                .body("pan", equalTo(pan));
    }

    private static Map<String, Object> mutate(String field, Object value) {
        Map<String, Object> body = CardApi.validCreateBody();
        body.put(field, value);
        return body;
    }

    private static Map<String, Object> removed(String field) {
        Map<String, Object> body = CardApi.validCreateBody();
        body.remove(field);
        return body;
    }
}
