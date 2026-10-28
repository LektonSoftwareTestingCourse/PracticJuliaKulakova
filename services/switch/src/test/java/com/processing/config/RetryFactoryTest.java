package com.processing.config;

import com.processing.Properties;
import io.github.resilience4j.retry.Retry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit-тесты фабрики retry-политик ({@link RetryFactory}).
 */
class RetryFactoryTest {

    @Test
    void shouldCreateAuthorizationRetryWithConfiguredBackoff() {
        Retry retry = RetryFactory.authorizationRetry(Properties.withBackoff(List.of(1L, 2L)));

        assertThat(retry.getName()).isEqualTo("authorization");
        assertThat(retry.getRetryConfig().getMaxAttempts()).isEqualTo(3);
    }

    @Test
    void shouldCreateLoggerRetryWithMinimumIntervalWhenBackoffIsEmpty() {
        Retry retry = RetryFactory.loggerRetry(Properties.withBackoff(List.of()));

        assertThat(retry.getName()).isEqualTo("logger");
        assertThat(retry.getRetryConfig().getMaxAttempts()).isEqualTo(3);
    }

    @Test
    void shouldCreateLoggerRetryWithMinimumIntervalWhenBackoffIsNull() {
        Retry retry = RetryFactory.loggerRetry(Properties.withBackoff(null));

        assertThat(retry.getName()).isEqualTo("logger");
    }

    @Test
    void shouldRetryConfiguredNumberOfAttemptsWithBackoffInterval() {
        Retry retry = RetryFactory.authorizationRetry(Properties.withBackoff(List.of(1L)));
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> Retry.decorateCallable(retry, () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("always fails");
        }).call())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("always fails");

        assertThat(calls.get()).isEqualTo(3);
    }
}
