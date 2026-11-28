package com.processing.integration.messaging;

import com.processing.common.dto.transactionlogger.TransactionRequest;
import com.processing.common.dto.transactionlogger.TransactionStatus;
import com.processing.integration.support.Ports;
import com.processing.integration.support.Postgres;
import com.processing.integration.support.Rabbit;
import com.processing.integration.support.ServiceProcess;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Poison message → DLQ: битый JSON уходит в {@code transaction-log-dlq},
 * основная очередь пуста, следующее валидное сообщение обрабатывается.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PoisonMessageDlqIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("transaction_logger")
        .withUsername("smp")
        .withPassword("smp");

    @Container
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management")
        .withAdminUser("smp")
        .withAdminPassword("smp");

    private final int loggerPort = Ports.freePort();

    private ServiceProcess transactionLogger;

    @BeforeAll
    void startLogger() {
        Map<String, String> environment = new HashMap<>(Postgres.datasourceEnv(
            postgres, "transaction_logger"));
        environment.put("SPRING_RABBITMQ_HOST", rabbit.getHost());
        environment.put("SPRING_RABBITMQ_PORT", String.valueOf(rabbit.getAmqpPort()));
        environment.put("SPRING_RABBITMQ_USERNAME", rabbit.getAdminUsername());
        environment.put("SPRING_RABBITMQ_PASSWORD", rabbit.getAdminPassword());
        environment.put("SPRING_AMQP_DESERIALIZATION_TRUST_ALL", "true");

        transactionLogger = ServiceProcess.start(
            "transaction-logger", loggerPort, environment, "/health");

        Rabbit.declareDeadLetterTopology(
            rabbit.getHost(), rabbit.getAmqpPort(),
            rabbit.getAdminUsername(), rabbit.getAdminPassword());

        Rabbit.awaitQueueDeclared(
            rabbit.getHost(), rabbit.getAmqpPort(),
            rabbit.getAdminUsername(), rabbit.getAdminPassword(),
            Duration.ofSeconds(60));
    }

    @AfterAll
    void stopLogger() {
        if (transactionLogger != null) {
            transactionLogger.close();
        }
    }

    @BeforeEach
    void purgeQueues() {
        Rabbit.purgeAll(
            rabbit.getHost(), rabbit.getAmqpPort(),
            rabbit.getAdminUsername(), rabbit.getAdminPassword());
    }

    @Test
    @DisplayName("Битый JSON уходит в DLQ, основная очередь пуста, сервис обрабатывает валидное сообщение")
    void shouldRoutePoisonMessageToDlqAndKeepServiceAlive() {
        byte[] brokenJson = "{\"id\":\"not-a-uuid\", stan:".getBytes(StandardCharsets.UTF_8);
        Rabbit.publishRaw(
            rabbit.getHost(), rabbit.getAmqpPort(),
            rabbit.getAdminUsername(), rabbit.getAdminPassword(), brokenJson);

        await()
            .atMost(Duration.ofSeconds(10))
            .pollInterval(Duration.ofMillis(50))
            .untilAsserted(() -> assertThat(Rabbit.depth(
                rabbit.getHost(), rabbit.getAmqpPort(),
                rabbit.getAdminUsername(), rabbit.getAdminPassword(), Rabbit.DLQ))
                .isEqualTo(1));

        String dlqBody = Rabbit.asText(Rabbit.peek(
            rabbit.getHost(), rabbit.getAmqpPort(),
            rabbit.getAdminUsername(), rabbit.getAdminPassword(), Rabbit.DLQ));
        assertThat(dlqBody).isEqualTo(new String(brokenJson, StandardCharsets.UTF_8));

        assertThat(Rabbit.depth(
            rabbit.getHost(), rabbit.getAmqpPort(),
            rabbit.getAdminUsername(), rabbit.getAdminPassword(), Rabbit.QUEUE))
            .isZero();

        TransactionRequest valid = validRequest();
        Rabbit.publishJavaSerialized(
            rabbit.getHost(), rabbit.getAmqpPort(),
            rabbit.getAdminUsername(), rabbit.getAdminPassword(), valid);

        await()
            .atMost(Duration.ofSeconds(30))
            .pollInterval(Duration.ofMillis(200))
            .untilAsserted(() -> assertThat(Postgres.queryCount(
                postgres, "transaction_logger",
                "SELECT COUNT(*) FROM transactions WHERE id = ?", valid.id()))
                .isEqualTo(1));

        assertThat(Rabbit.depth(
            rabbit.getHost(), rabbit.getAmqpPort(),
            rabbit.getAdminUsername(), rabbit.getAdminPassword(), Rabbit.QUEUE))
            .isZero();
        assertThat(transactionLogger.isAlive()).isTrue();
    }

    private static TransactionRequest validRequest() {
        Instant now = Instant.now();
        return new TransactionRequest(
            UUID.randomUUID(),
            "0100",
            String.format("%06d", System.nanoTime() % 1_000_000L),
            "123456789012",
            "4000001234567893",
            "000000",
            new BigDecimal("10000"),
            "643",
            "TERM0001",
            "POS",
            "MERCH0000000001",
            "5411",
            "ACQ001",
            "ISS001",
            BigDecimal.ZERO,
            TransactionStatus.APPROVED,
            null,
            "TEST01",
            12,
            now,
            now
        );
    }
}
