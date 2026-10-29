package com.processing.gateway.logging;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-тесты маскирования чувствительных данных в логах ({@link LogDataMasker}).
 */
class LogDataMaskerTest {

    private final LogDataMasker masker = new LogDataMasker();

    @Test
    void shouldMaskPanInJsonPayloadKeepingFirstSixAndLastFourDigits() {
        String payload = "{\"pan\":\"4000001234567890\",\"amount\":1000}";

        String masked = masker.maskData(payload);

        assertThat(masked).isEqualTo("{\"pan\":\"400000****7890\",\"amount\":1000}");
    }

    @Test
    void shouldMaskThreeDigitCvvInJsonPayload() {
        String payload = "{\"cvv\":\"123\"}";

        String masked = masker.maskData(payload);

        assertThat(masked).isEqualTo("{\"cvv\":\"***\"}");
    }

    @Test
    void shouldMaskFourDigitCvvInJsonPayload() {
        String payload = "{\"cvv\":\"1234\"}";

        String masked = masker.maskData(payload);

        assertThat(masked).isEqualTo("{\"cvv\":\"***\"}");
    }

    @Test
    void shouldMaskPanAndCvvInSamePayloadRegardlessOfCaseAndSpacing() {
        String payload = "{\"PAN\": \"4000001234567890\", \"cvv\" : \"777\", \"stan\":\"000001\"}";

        String masked = masker.maskData(payload);

        assertThat(masked).doesNotContain("4000001234567890");
        assertThat(masked).doesNotContain("777");
        assertThat(masked).contains("400000****7890");
        assertThat(masked).contains("***");
        assertThat(masked).contains("000001");
    }

    @Test
    void shouldReturnDataUnchangedWhenNoSensitiveFieldsPresent() {
        String payload = "{\"stan\":\"000001\",\"amount\":1000}";

        String masked = masker.maskData(payload);

        assertThat(masked).isEqualTo(payload);
    }
}
