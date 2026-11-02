package com.processing.gateway.validation;

import com.processing.common.dto.authorization.AuthorizationRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit-тесты валидатора транзакций Gateway ({@link TransactionRequestValidator}).
 * Классы эквивалентности «валидный запрос» / «невалидный запрос» и граничные
 * значения длины полей реализованы параметризованными тестами.
 */
class TransactionRequestValidatorTest {

    private static final String VALID_PAN = "4000001234567890";
    private static final Instant TRANSMISSION_TIME = Instant.parse("2026-10-26T12:00:00Z");

    private final TransactionRequestValidator validator = new TransactionRequestValidator();

    @Test
    void shouldPassValidationWhenRequestIsFullyValid() {
        AuthorizationRequest request = validRequest();

        assertThatCode(() -> validator.validate(request)).doesNotThrowAnyException();
    }

    @Test
    void shouldThrowValidationExceptionWhenRequestIsNull() {
        assertThatThrownBy(() -> validator.validate(null))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Request body is required");
    }

    @ParameterizedTest(name = "missing field: {0}")
    @MethodSource("requestsWithMissingRequiredField")
    void shouldThrowValidationExceptionWhenRequiredFieldIsMissing(
            String fieldName, AuthorizationRequest request) {
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Field '" + fieldName + "' is required");
    }

    @Test
    void shouldThrowValidationExceptionWhenMtiIsNot0100() {
        AuthorizationRequest request = requestWithMti("0200");

        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Field 'mti' must be '0100'");
    }

    @ParameterizedTest(name = "pan='{0}'")
    @CsvSource({
            "400000123456789",
            "40000012345678901",
            "40000012345678ab",
            "4000 01234567890"
    })
    void shouldThrowValidationExceptionWhenPanIsNotExactlySixteenDigits(String pan) {
        AuthorizationRequest request = requestWithPan(pan);

        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Field 'pan' must be exactly 16 digits");
    }

    @Test
    void shouldThrowValidationExceptionWhenAmountIsNull() {
        AuthorizationRequest request = requestWithAmount(null);

        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Field 'amount' is required");
    }

    @ParameterizedTest(name = "amount={0}")
    @CsvSource({"0", "-1", "-0.01"})
    void shouldThrowValidationExceptionWhenAmountIsNotPositive(BigDecimal amount) {
        AuthorizationRequest request = requestWithAmount(amount);

        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Field 'amount' must be > 0");
    }

    @ParameterizedTest(name = "currencyCode='{0}'")
    @CsvSource({"64", "6431", "RU"})
    void shouldThrowValidationExceptionWhenCurrencyCodeIsNotThreeCharacters(String currencyCode) {
        AuthorizationRequest request = requestWithCurrencyCode(currencyCode);

        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Field 'currencyCode' must be exactly 3 characters");
    }

    @ParameterizedTest(name = "mcc='{0}'")
    @CsvSource({"504", "50455", "abcd", "50 5"})
    void shouldThrowValidationExceptionWhenMccIsNotFourDigits(String mcc) {
        AuthorizationRequest request = requestWithMcc(mcc);

        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Field 'mcc' must be exactly 4 digits");
    }

    private static Stream<Arguments> requestsWithMissingRequiredField() {
        return Stream.of(
                Arguments.of("mti", validRequestBuilder().mti(null).build()),
                Arguments.of("stan", validRequestBuilder().stan(" ").build()),
                Arguments.of("pan", validRequestBuilder().pan(null).build()),
                Arguments.of("processingCode", validRequestBuilder().processingCode("").build()),
                Arguments.of("currencyCode", validRequestBuilder().currencyCode(null).build()),
                Arguments.of("transmissionDateTime", validRequestBuilder().transmissionDateTime(null).build()),
                Arguments.of("terminalId", validRequestBuilder().terminalId(null).build()),
                Arguments.of("merchantId", validRequestBuilder().merchantId("  ").build()),
                Arguments.of("mcc", validRequestBuilder().mcc(null).build()),
                Arguments.of("acquirerId", validRequestBuilder().acquirerId("").build()));
    }

    private static AuthorizationRequest validRequest() {
        return validRequestBuilder().build();
    }

    private static AuthorizationRequest.AuthorizationRequestBuilder validRequestBuilder() {
        return AuthorizationRequest.builder()
                .mti("0100")
                .stan("000001")
                .pan(VALID_PAN)
                .processingCode("000000")
                .amount(new BigDecimal("1000"))
                .currencyCode("643")
                .transmissionDateTime(TRANSMISSION_TIME)
                .terminalId("TERM0001")
                .terminalType("POS")
                .merchantId("MERCH0000000002")
                .mcc("5045")
                .acquirerId("ACQ002")
                .issuerId("ISS001");
    }

    private AuthorizationRequest requestWithMti(String mti) {
        return validRequestBuilder().mti(mti).build();
    }

    private AuthorizationRequest requestWithPan(String pan) {
        return validRequestBuilder().pan(pan).build();
    }

    private AuthorizationRequest requestWithAmount(BigDecimal amount) {
        return validRequestBuilder().amount(amount).build();
    }

    private AuthorizationRequest requestWithCurrencyCode(String currencyCode) {
        return validRequestBuilder().currencyCode(currencyCode).build();
    }

    private AuthorizationRequest requestWithMcc(String mcc) {
        return validRequestBuilder().mcc(mcc).build();
    }
}
