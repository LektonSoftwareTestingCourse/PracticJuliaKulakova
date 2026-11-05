package com.processing.apitests.support;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Билдер тела запроса авторизации (POST /api/transactions).
 * Значения по умолчанию соответствуют контракту AuthorizationRequest
 * (docs/api-spec.md, раздел «Модели данных»).
 */
public final class AuthRequestBuilder {

    private final Map<String, Object> body = new LinkedHashMap<>();

    private AuthRequestBuilder() {
    }

    public static AuthRequestBuilder valid(String pan) {
        AuthRequestBuilder builder = new AuthRequestBuilder();
        builder.body.put("mti", "0100");
        builder.body.put("stan", TestData.nextStan());
        builder.body.put("pan", pan);
        builder.body.put("processingCode", "000000");
        builder.body.put("amount", 1000L);
        builder.body.put("currencyCode", "643");
        builder.body.put("transmissionDateTime", TestData.now().toString());
        builder.body.put("terminalId", TestData.nextTerminalId());
        builder.body.put("terminalType", "POS");
        builder.body.put("merchantId", TestData.nextMerchantId());
        builder.body.put("mcc", "5411");
        builder.body.put("acquirerId", "ACQ001");
        return builder;
    }

    public AuthRequestBuilder with(String field, Object value) {
        body.put(field, value);
        return this;
    }

    public AuthRequestBuilder without(String field) {
        body.remove(field);
        return this;
    }

    public String stan() {
        return String.valueOf(body.get("stan"));
    }

    public String merchantId() {
        return String.valueOf(body.get("merchantId"));
    }

    public Map<String, Object> build() {
        return body;
    }
}
