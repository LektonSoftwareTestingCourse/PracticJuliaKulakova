package com.processing;

import com.processing.config.SwitchProperties;

import java.util.List;
import java.util.Map;

/**
 * Object Mother для конфигурации {@link com.processing.config.SwitchProperties} в unit-тестах.
 */
public final class Properties {

    public static final String AUTHORIZATION_URL = "http://authorization:8081";
    public static final String LOGGER_URL = "http://logger:8083";
    public static final String MERCHANT_ACQUIRER_URL = "http://merchant-acquirer:8085";
    public static final String KNOWN_BIN = "400000";
    public static final String ISSUER_ID = "ISS001";

    private Properties() {
    }

    public static SwitchProperties defaults() {
        return withBinRouting(Map.of(KNOWN_BIN, ISSUER_ID));
    }

    public static SwitchProperties withBinRouting(Map<String, String> binRouting) {
        return properties(binRouting, new SwitchProperties.RetryProperties(3, List.of(100L)));
    }

    public static SwitchProperties withBackoff(List<Long> backoffMs) {
        return properties(Map.of(KNOWN_BIN, ISSUER_ID), new SwitchProperties.RetryProperties(3, backoffMs));
    }

    public static SwitchProperties withRetryAttempts(int maxAttempts) {
        return properties(Map.of(KNOWN_BIN, ISSUER_ID), new SwitchProperties.RetryProperties(maxAttempts, List.of(1L)));
    }

    private static SwitchProperties properties(
            Map<String, String> binRouting,
            SwitchProperties.RetryProperties retry) {
        return new SwitchProperties(
                "1.0.0",
                binRouting,
                AUTHORIZATION_URL,
                LOGGER_URL,
                MERCHANT_ACQUIRER_URL,
                new SwitchProperties.HttpProperties(1000, 2000, 3000),
                retry,
                new SwitchProperties.CircuitBreakerSection(
                        new SwitchProperties.CircuitBreakerProperties(50, 10, 5, 1000L)));
    }
}
