package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-тесты нормализации идентификаторов терминала и мерчанта
 * ({@link AuthorizationRequestNormalizer}). Таблицы вход → ожидание
 * реализованы параметризованными тестами.
 */
class AuthorizationRequestNormalizerTest {

    private static final Instant TRANSMISSION_TIME = Instant.parse("2026-10-26T12:00:00Z");

    @ParameterizedTest(name = "terminalId='{0}' -> '{1}'")
    @CsvSource({
            "TERM0001,       TERM0001",
            "TERM00012345,   TERM0001",
            "TERM1,          TERM1000",
            "T,              T0000000",
            "TERM000,        TERM0000"
    })
    void shouldNormalizeTerminalIdToEightCharacters(String terminalId, String expected) {
        String normalized = AuthorizationRequestNormalizer.normalizeTerminalId(terminalId);

        assertThat(normalized).isEqualTo(expected);
    }

    @Test
    void shouldKeepTerminalIdUnchangedWhenItIsNullOrBlank() {
        assertThat(AuthorizationRequestNormalizer.normalizeTerminalId(null)).isNull();
        assertThat(AuthorizationRequestNormalizer.normalizeTerminalId("   ")).isEqualTo("   ");
    }

    @ParameterizedTest(name = "merchantId='{0}' -> '{1}'")
    @CsvSource({
            "MERCH0000000002,    MERCH0000000002",
            "MERCH00000000002,   MERCH0000000002",
            "MERCH10000000002,   MERCH1000000000",
            "X123456789012345,   X12345678901234",
            "MERCH1,             MERCH1000000000",
            "M,                  M00000000000000"
    })
    void shouldNormalizeMerchantIdToFifteenCharacters(String merchantId, String expected) {
        String normalized = AuthorizationRequestNormalizer.normalizeMerchantId(merchantId);

        assertThat(normalized).isEqualTo(expected);
    }

    @Test
    void shouldKeepMerchantIdUnchangedWhenItIsNullOrBlank() {
        assertThat(AuthorizationRequestNormalizer.normalizeMerchantId(null)).isNull();
        assertThat(AuthorizationRequestNormalizer.normalizeMerchantId(" ")).isEqualTo(" ");
    }

    @Test
    void shouldCopyAllFieldsAndNormalizeIdentifiersWhenNormalizingRequest() {
        AuthorizationRequest request = requestWithIdentifiers("TERM1", "MERCH1");

        AuthorizationRequest normalized = AuthorizationRequestNormalizer.normalize(request);

        assertThat(normalized.terminalId()).isEqualTo("TERM1000");
        assertThat(normalized.merchantId()).isEqualTo("MERCH1000000000");
        assertThat(normalized.mti()).isEqualTo(request.mti());
        assertThat(normalized.stan()).isEqualTo(request.stan());
        assertThat(normalized.pan()).isEqualTo(request.pan());
        assertThat(normalized.processingCode()).isEqualTo(request.processingCode());
        assertThat(normalized.amount()).isEqualByComparingTo(request.amount());
        assertThat(normalized.currencyCode()).isEqualTo(request.currencyCode());
        assertThat(normalized.transmissionDateTime()).isEqualTo(request.transmissionDateTime());
        assertThat(normalized.terminalType()).isEqualTo(request.terminalType());
        assertThat(normalized.mcc()).isEqualTo(request.mcc());
        assertThat(normalized.acquirerId()).isEqualTo(request.acquirerId());
        assertThat(normalized.issuerId()).isEqualTo(request.issuerId());
    }

    private AuthorizationRequest requestWithIdentifiers(String terminalId, String merchantId) {
        return new AuthorizationRequest(
                "0100", "000001", "4000001234567890", "000000",
                new BigDecimal("1000"), "643", TRANSMISSION_TIME,
                terminalId, "POS", merchantId, "5045", "ACQ002", "ISS001");
    }
}
