package com.processing.config;

import com.processing.Properties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-тесты фабрики circuit breaker ({@link CircuitBreakerFactory}).
 */
class CircuitBreakerFactoryTest {

    @Test
    void shouldCreateClosedCircuitBreakerFromProperties() {
        CircuitBreaker circuitBreaker = CircuitBreakerFactory
                .authorizationCircuitBreaker(Properties.defaults());

        assertThat(circuitBreaker.getName()).isEqualTo("authorization");
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuitBreaker.getCircuitBreakerConfig().getFailureRateThreshold()).isEqualTo(50f);
        assertThat(circuitBreaker.getCircuitBreakerConfig().getSlidingWindowSize()).isEqualTo(10);
    }

    @Test
    void shouldTransitionToOpenStateWhenForced() {
        CircuitBreaker circuitBreaker = CircuitBreakerFactory
                .authorizationCircuitBreaker(Properties.defaults());

        circuitBreaker.transitionToOpenState();

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }
}
