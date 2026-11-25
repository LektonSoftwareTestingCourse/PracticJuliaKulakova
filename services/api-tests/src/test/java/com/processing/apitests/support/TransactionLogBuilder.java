package com.processing.apitests.support;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Билдер тела запроса для POST /api/internal/log (transaction-logger).
 * Значения по умолчанию соответствуют контракту TransactionRequest
 * (services/common/.../transactionlogger/TransactionRequest.java).
 */
public final class TransactionLogBuilder {

    private final Map<String, Object> body = new LinkedHashMap<>();

    private TransactionLogBuilder() {
    }

    public static TransactionLogBuilder approved(String pan) {
        TransactionLogBuilder builder = new TransactionLogBuilder();
        builder.body.put("id", TestData.newId());
        builder.body.put("mti", "0100");
        builder.body.put("stan", TestData.nextStan());
        builder.body.put("rrn", TestData.nextRrn());
        builder.body.put("pan", pan);
        builder.body.put("processingCode", "000000");
        builder.body.put("amount", 150000L);
        builder.body.put("currencyCode", "643");
        builder.body.put("terminalId", TestData.nextTerminalId());
        builder.body.put("terminalType", "POS");
        builder.body.put("merchantId", TestData.nextMerchantId());
        builder.body.put("mcc", "5411");
        builder.body.put("acquirerId", "ACQ001");
        builder.body.put("issuerId", "ISS001");
        builder.body.put("acquiringFee", 1500L);
        builder.body.put("status", "APPROVED");
        builder.body.put("declineReason", null);
        builder.body.put("authCode", "ABC123");
        builder.body.put("processingTimeMs", 42);
        builder.body.put("transmissionDateTime", TestData.now().toString());
        builder.body.put("createdAt", TestData.now().toString());
        return builder;
    }

    public static TransactionLogBuilder declined(String pan, String declineReason) {
        TransactionLogBuilder builder = approved(pan);
        builder.body.put("status", "DECLINED");
        builder.body.put("declineReason", declineReason);
        builder.body.put("rrn", null);
        builder.body.put("authCode", null);
        return builder;
    }

    public TransactionLogBuilder with(String field, Object value) {
        body.put(field, value);
        return this;
    }

    public TransactionLogBuilder without(String field) {
        body.remove(field);
        return this;
    }

    public String id() {
        return String.valueOf(body.get("id"));
    }

    public String stan() {
        return String.valueOf(body.get("stan"));
    }

    public String pan() {
        return String.valueOf(body.get("pan"));
    }

    public String merchantId() {
        return String.valueOf(body.get("merchantId"));
    }

    public Map<String, Object> build() {
        return body;
    }
}
