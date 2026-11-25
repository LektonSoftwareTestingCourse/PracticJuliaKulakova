package com.processing.apitests;

import com.processing.apitests.support.AuthRequestBuilder;
import com.processing.apitests.support.CardApi;
import com.processing.apitests.support.SearchApi;
import com.processing.apitests.support.Specs;
import com.processing.apitests.support.TestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;

/**
 * Асинхронные эффекты: switch публикует транзакцию в RabbitMQ,
 * transaction-logger сохраняет её асинхронно. Ожидание — через Awaitility
 * с осознанными границами (atMost/pollInterval), фиксированных пауз нет.
 */
class AsyncEffectsApiTest {

    /** Предел ожидания: доставка через RabbitMQ в норме занимает доли секунды. */
    private static final Duration APPEAR_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);

    /** Окно наблюдения для проверки отсутствия эффекта. */
    private static final Duration ABSENCE_WINDOW = Duration.ofSeconds(3);

    @Test
    @DisplayName("Одобренная транзакция появляется в поиске (асинхронное логирование)")
    void approvedTransactionAppearsInSearch() {
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
                                .then()
                                .statusCode(200)
                                .body("total", equalTo(1))
                                .body("transactions[0].stan", equalTo(request.stan())));
    }

    @Test
    @DisplayName("Отклонённая транзакция тоже сохраняется с причиной отказа")
    void declinedTransactionAppearsInSearchWithReason() {
        String pan = CardApi.createActiveCard();
        CardApi.changeStatus(pan, "BLOCKED");
        AuthRequestBuilder request = AuthRequestBuilder.valid(pan);

        given()
                .spec(Specs.gateway())
                .body(request.build())
        .when()
                .post("/api/transactions")
        .then()
                .statusCode(200)
                .body("status", equalTo("DECLINED"))
                .body("declineReason", equalTo("CARD_BLOCKED"));

        await("отклонённая транзакция должна появиться в хранилище")
                .atMost(APPEAR_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .untilAsserted(() ->
                        SearchApi.searchByPan(pan)
                                .then()
                                .statusCode(200)
                                .body("total", equalTo(1))
                                .body("transactions[0].status", equalTo("DECLINED"))
                                .body("transactions[0].declineReason", equalTo("CARD_BLOCKED")));
    }

    @Test
    @DisplayName("Запрос, отклонённый валидацией Gateway, не попадает в хранилище (ожидание отсутствия)")
    void rejectedTransactionIsNeverStored() {
        String pan = TestData.panWithoutCard();
        AuthRequestBuilder request = AuthRequestBuilder.valid(pan).with("mcc", "54");

        given()
                .spec(Specs.gateway())
                .body(request.build())
        .when()
                .post("/api/transactions")
        .then()
                .statusCode(400);

        // Проверка отсутствия эффекта: условие должно оставаться истинным
        await("отклонённый запрос не должен сохраняться")
                .during(ABSENCE_WINDOW)
                .atMost(ABSENCE_WINDOW.plusSeconds(2))
                .pollInterval(POLL_INTERVAL)
                .until(() -> SearchApi.isAbsent(pan));
    }

    @Test
    @DisplayName("Отклонённая транзакция не резервирует средства: баланс не меняется (ожидание отсутствия)")
    void declinedTransactionDoesNotReserveFunds() {
        String pan = CardApi.createActiveCard(TestData.KNOWN_BIN, 1_000L, 1_000_000L, 10_000_000L);
        long balanceBefore = CardApi.availableBalance(pan);

        given()
                .spec(Specs.gateway())
                .body(AuthRequestBuilder.valid(pan).with("amount", 500_000L).build())
        .when()
                .post("/api/transactions")
        .then()
                .statusCode(200)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("51"));

        await("резервирование не должно произойти")
                .during(ABSENCE_WINDOW)
                .atMost(ABSENCE_WINDOW.plusSeconds(2))
                .pollInterval(POLL_INTERVAL)
                .until(() -> CardApi.balanceQuietly(pan) == balanceBefore);
    }
}
