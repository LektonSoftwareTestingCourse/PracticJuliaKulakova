package com.processing.service;

import com.processing.common.dto.transactionlogger.TransactionRequest;
import com.processing.common.dto.transactionlogger.TransactionStatus;
import com.processing.config.RabbitMQConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * Unit-тесты публикации транзакций в RabbitMQ ({@link LoggerClient}).
 */
@ExtendWith(MockitoExtension.class)
class LoggerClientTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    private LoggerClient loggerClient;

    @BeforeEach
    void setUp() {
        loggerClient = new LoggerClient(rabbitTemplate);
    }

    @Test
    void shouldReturnTrueWhenBrokerAcceptsTransaction() {
        TransactionRequest transaction = transaction();

        boolean logged = loggerClient.log(transaction);

        assertThat(logged).isTrue();
        verify(rabbitTemplate).convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.ROUTING_KEY, transaction);
    }

    @Test
    void shouldReturnFalseWhenBrokerThrowsAmqpException() {
        doThrow(new AmqpException("broker is down"))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));

        boolean logged = loggerClient.log(transaction());

        assertThat(logged).isFalse();
    }

    private TransactionRequest transaction() {
        return new TransactionRequest(
                UUID.randomUUID(), "0100", "000001", "629712345000", "4000001234567890", "000000",
                new BigDecimal("1000"), "643", "TERM0001", "POS", "MERCH0000000002", "5045",
                "ACQ002", "ISS001", BigDecimal.ONE, TransactionStatus.APPROVED, null, "ABC123",
                42, Instant.parse("2026-10-26T12:00:00Z"), Instant.parse("2026-10-26T12:00:01Z"));
    }
}
