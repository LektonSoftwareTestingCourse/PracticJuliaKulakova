package com.processing.integration.chain;

import com.processing.integration.support.ChainEnvironment;
import com.processing.integration.support.Http;
import com.processing.integration.support.Postgres;
import com.processing.integration.support.Rabbit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Сквозной проход транзакции через Switch → Authorization → Card Management →
 * Transaction Logger со следами в трёх БД и в брокере.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChainAuthorizationIT {

    private static final String BIN = "400000";
    private static final BigDecimal DAILY_LIMIT = new BigDecimal("15000000");
    private static final BigDecimal MONTHLY_LIMIT = new BigDecimal("300000000");
    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("1000000");
    private static final BigDecimal TRANSACTION_AMOUNT = new BigDecimal("10000");

    private static final Instant TRANSMISSION_DATE_TIME = Instant.parse("2026-06-01T10:30:00Z");
    private static final LocalDate USAGE_DATE = LocalDate.of(2026, 6, 1);

    private ChainEnvironment environment;

    @BeforeAll
    void startEnvironment() {
        environment = ChainEnvironment.start();
    }

    @AfterAll
    void stopEnvironment() {
        if (environment != null) {
            environment.close();
        }
    }

    @BeforeEach
    void cleanData() {
        Postgres.execute(environment.postgres(), Postgres.CARD_MANAGEMENT_DB,
            "TRUNCATE TABLE reservation_rollbacks, reservations, cards, outbox_event CASCADE");
        Postgres.execute(environment.postgres(), Postgres.AUTHORIZATION_DB,
            "TRUNCATE TABLE limit_usage CASCADE");
        Postgres.execute(environment.postgres(), Postgres.TRANSACTION_LOGGER_DB,
            "TRUNCATE TABLE transactions CASCADE");
        Rabbit.purgeAll(environment.rabbitHost(), environment.rabbitPort(),
            environment.rabbitUser(), environment.rabbitPassword());
    }

    @Test
    @DisplayName("APPROVED-транзакция оставляет следы в БД всех трёх сервисов и в брокере")
    void shouldApproveTransactionAndLeaveTracesInAllDatabases() {
        String pan = createCard();
        String stan = uniqueStan(1);

        Http.Response response = authorize(pan, stan);

        assertThat(response.status())
            .as("Ответ Switch: %s", response.body())
            .isEqualTo(200);
        assertThat(response.stringField("status")).isEqualTo("APPROVED");
        assertThat(response.stringField("responseCode")).isEqualTo("00");
        String rrn = response.stringField("rrn");
        assertThat(rrn).isNotBlank();

        await()
            .atMost(Duration.ofSeconds(30))
            .pollInterval(Duration.ofMillis(200))
            .untilAsserted(() -> assertThat(Postgres.queryCount(
                environment.postgres(), Postgres.TRANSACTION_LOGGER_DB,
                "SELECT COUNT(*) FROM transactions WHERE rrn = ?", rrn)).isEqualTo(1));

        List<Object> logged = Postgres.querySingleRow(
            environment.postgres(), Postgres.TRANSACTION_LOGGER_DB,
            "SELECT pan, stan, status, amount FROM transactions WHERE rrn = ?", rrn);
        assertThat(logged).hasSize(4);
        assertThat(logged.get(0)).isEqualTo(pan);
        assertThat(logged.get(1)).isEqualTo(stan);
        assertThat(logged.get(2)).isEqualTo("APPROVED");
        assertThat((BigDecimal) logged.get(3)).isEqualByComparingTo(TRANSACTION_AMOUNT);

        List<Object> limit = Postgres.querySingleRow(
            environment.postgres(), Postgres.AUTHORIZATION_DB,
            "SELECT daily_amount, monthly_amount FROM limit_usage WHERE pan = ? AND usage_date = ?",
            pan, USAGE_DATE);
        assertThat(limit).hasSize(2);
        assertThat((BigDecimal) limit.get(0)).isEqualByComparingTo(TRANSACTION_AMOUNT);
        assertThat((BigDecimal) limit.get(1)).isEqualByComparingTo(TRANSACTION_AMOUNT);

        List<Object> reservation = Postgres.querySingleRow(
            environment.postgres(), Postgres.CARD_MANAGEMENT_DB,
            "SELECT reservation_amount FROM reservations WHERE rrn = ?", rrn);
        assertThat(reservation).hasSize(1);
        assertThat((BigDecimal) reservation.getFirst()).isEqualByComparingTo(TRANSACTION_AMOUNT);

        List<Object> balance = Postgres.querySingleRow(
            environment.postgres(), Postgres.CARD_MANAGEMENT_DB,
            "SELECT available_balance FROM cards WHERE pan = ?", pan);
        assertThat((BigDecimal) balance.getFirst())
            .isEqualByComparingTo(INITIAL_BALANCE.subtract(TRANSACTION_AMOUNT));
    }

    @Test
    @DisplayName("Две транзакции с уникальными STAN/RRN не конфликтуют и накапливают дневной лимит")
    void shouldHandleTwoTransactionsWithUniqueKeys() {
        String pan = createCard();
        String firstStan = uniqueStan(1);
        String secondStan = uniqueStan(2);

        Http.Response first = authorize(pan, firstStan);
        Http.Response second = authorize(pan, secondStan);

        assertThat(first.stringField("status")).isEqualTo("APPROVED");
        assertThat(second.stringField("status")).isEqualTo("APPROVED");
        assertThat(first.stringField("rrn")).isNotEqualTo(second.stringField("rrn"));

        String firstRrn = first.stringField("rrn");
        String secondRrn = second.stringField("rrn");

        await()
            .atMost(Duration.ofSeconds(30))
            .pollInterval(Duration.ofMillis(200))
            .untilAsserted(() -> assertThat(Postgres.queryCount(
                environment.postgres(), Postgres.TRANSACTION_LOGGER_DB,
                "SELECT COUNT(*) FROM transactions WHERE rrn IN (?, ?)", firstRrn, secondRrn))
                .isEqualTo(2));

        assertThat(Postgres.queryCount(
            environment.postgres(), Postgres.AUTHORIZATION_DB,
            "SELECT COUNT(*) FROM limit_usage WHERE pan = ?", pan)).isEqualTo(1);
        List<Object> daily = Postgres.querySingleRow(
            environment.postgres(), Postgres.AUTHORIZATION_DB,
            "SELECT daily_amount FROM limit_usage WHERE pan = ?", pan);
        assertThat((BigDecimal) daily.getFirst())
            .isEqualByComparingTo(TRANSACTION_AMOUNT.multiply(BigDecimal.TWO));
    }

    private Http.Response authorize(String pan, String stan) {
        return Http.post(environment.switchUrl() + "/api/internal/route",
            authorizationRequest(pan, stan));
    }

    private String createCard() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("bin", BIN);
        request.put("cardholderName", "IVAN IVANOV");
        request.put("currencyCode", "643");
        request.put("dailyLimit", DAILY_LIMIT);
        request.put("monthlyLimit", MONTHLY_LIMIT);
        request.put("initialBalance", INITIAL_BALANCE);

        Http.Response created = Http.post(environment.cardManagementUrl() + "/api/cards", request);
        assertThat(created.status())
            .as("Создание карты: %s", created.body())
            .isEqualTo(201);
        String pan = created.stringField("pan");
        assertThat(pan).hasSize(16);
        return pan;
    }

    private static String uniqueStan(int offset) {
        long value = (System.nanoTime() / 1000L + offset) % 1_000_000L;
        return String.format("%06d", value);
    }

    private static Map<String, Object> authorizationRequest(String pan, String stan) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("mti", "0100");
        request.put("stan", stan);
        request.put("pan", pan);
        request.put("processingCode", "000000");
        request.put("amount", TRANSACTION_AMOUNT);
        request.put("currencyCode", "643");
        request.put("transmissionDateTime", TRANSMISSION_DATE_TIME.toString());
        request.put("terminalId", "TERM0001");
        request.put("terminalType", "POS");
        request.put("merchantId", "MERCH0000000001");
        request.put("mcc", "5411");
        request.put("acquirerId", "ACQ001");
        request.put("issuerId", "ISS001");
        return request;
    }
}
