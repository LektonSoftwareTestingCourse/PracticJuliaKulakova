package com.processing.gateway.ratelimit;

import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-тесты token-bucket ограничителя частоты запросов ({@link InMemoryRateLimiter}).
 * Время детерминированно контролируется фейковым {@link Ticker} — без Thread.sleep.
 */
class InMemoryRateLimiterTest {

    private static final String KEY = "POST /api/transactions:127.0.0.1";
    private static final String OTHER_KEY = "POST /api/transactions:10.0.0.1";
    private static final Duration TEN_MINUTES = Duration.ofMinutes(10);

    private final AtomicLong nanos = new AtomicLong();
    private final Ticker fakeTicker = nanos::get;

    @Test
    void shouldAllowRequestsWithinCapacityAndDenyAfterExhaustion() {
        InMemoryRateLimiter rateLimiter = rateLimiter(3, 0);

        assertThat(rateLimiter.allowRequest(KEY)).isTrue();
        assertThat(rateLimiter.allowRequest(KEY)).isTrue();
        assertThat(rateLimiter.allowRequest(KEY)).isTrue();
        assertThat(rateLimiter.allowRequest(KEY)).isFalse();
    }

    @Test
    void shouldRefillTokensWhenTimePasses() {
        InMemoryRateLimiter rateLimiter = rateLimiter(2, 1);
        rateLimiter.allowRequest(KEY);
        rateLimiter.allowRequest(KEY);
        assertThat(rateLimiter.allowRequest(KEY)).isFalse();

        nanos.addAndGet(Duration.ofSeconds(1).toNanos());

        assertThat(rateLimiter.allowRequest(KEY)).isTrue();
        assertThat(rateLimiter.allowRequest(KEY)).isFalse();
    }

    @Test
    void shouldNotAccumulateMoreTokensThanCapacity() {
        InMemoryRateLimiter rateLimiter = rateLimiter(2, 100);
        rateLimiter.allowRequest(KEY);

        nanos.addAndGet(Duration.ofMinutes(5).toNanos());

        assertThat(rateLimiter.allowRequest(KEY)).isTrue();
        assertThat(rateLimiter.allowRequest(KEY)).isTrue();
        assertThat(rateLimiter.allowRequest(KEY)).isFalse();
    }

    @Test
    void shouldTrackBucketsIndependentlyForEachKey() {
        InMemoryRateLimiter rateLimiter = rateLimiter(1, 0);
        rateLimiter.allowRequest(KEY);

        assertThat(rateLimiter.allowRequest(KEY)).isFalse();
        assertThat(rateLimiter.allowRequest(OTHER_KEY)).isTrue();
    }

    @Test
    void shouldDenyEveryRequestWhenCapacityIsZero() {
        InMemoryRateLimiter rateLimiter = rateLimiter(0, 1);

        assertThat(rateLimiter.allowRequest(KEY)).isFalse();
    }

    private InMemoryRateLimiter rateLimiter(int capacity, double refillTokensPerSecond) {
        return InMemoryRateLimiter.forTesting(capacity, refillTokensPerSecond, TEN_MINUTES, 1000, fakeTicker);
    }
}
