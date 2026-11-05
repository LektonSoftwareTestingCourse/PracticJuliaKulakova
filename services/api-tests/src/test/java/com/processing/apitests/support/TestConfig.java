package com.processing.apitests.support;

/**
 * Базовые адреса сервисов СМП.
 * Значения по умолчанию соответствуют docker-compose и .env.example;
 * переопределяются системным свойством {@code <service>.baseUrl}.
 */
public final class TestConfig {

    private TestConfig() {
    }

    public static String gatewayUrl() {
        return baseUrl("gateway", "http://localhost:8080");
    }

    public static String cardManagementUrl() {
        return baseUrl("cardmanagement", "http://localhost:8081");
    }

    public static String switchUrl() {
        return baseUrl("switch", "http://localhost:8082");
    }

    public static String authorizationUrl() {
        return baseUrl("authorization", "http://localhost:8083");
    }

    public static String merchantSimulatorUrl() {
        return baseUrl("merchant", "http://localhost:8084");
    }

    public static String terminalSimulatorUrl() {
        return baseUrl("terminal", "http://localhost:8085");
    }

    public static String loggerUrl() {
        return baseUrl("logger", "http://localhost:8088");
    }

    private static String baseUrl(String service, String defaultValue) {
        return System.getProperty(service + ".baseUrl", defaultValue);
    }
}
