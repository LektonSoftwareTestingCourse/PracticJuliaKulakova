package com.processing.cardmanagement.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-тесты алгоритма Луна ({@link LuhnValidator}):
 * классы эквивалентности «валидный PAN» / «невалидный PAN» и генерация.
 */
class LuhnValidatorTest {

    private static final String BIN = "400000";

    private final LuhnValidator luhnValidator = new LuhnValidator();

    @ParameterizedTest(name = "valid pan={0}")
    @ValueSource(strings = {
            "4539578763621486",
            "5555555555554444",
            "4000056655665556",
            "0000000000000000"
    })
    void shouldReturnTrueWhenPanPassesLuhnCheck(String pan) {
        boolean valid = luhnValidator.isValid(pan);

        assertThat(valid).isTrue();
    }

    @ParameterizedTest(name = "invalid pan={0}")
    @ValueSource(strings = {
            "4000001234567890",
            "1234567812345678",
            "4539578763621487",
            "0000000000000001"
    })
    void shouldReturnFalseWhenPanFailsLuhnCheck(String pan) {
        boolean valid = luhnValidator.isValid(pan);

        assertThat(valid).isFalse();
    }

    @Test
    void shouldGenerateSixteenDigitLuhnValidPanWithGivenBin() {
        String pan = luhnValidator.generatePan(BIN);

        assertThat(pan).hasSize(16).startsWith(BIN).matches("\\d{16}");
        assertThat(luhnValidator.isValid(pan)).isTrue();
    }

    @Test
    void shouldGenerateDifferentPansForTheSameBin() {
        String firstPan = luhnValidator.generatePan(BIN);
        String secondPan = luhnValidator.generatePan(BIN);

        assertThat(firstPan).isNotEqualTo(secondPan);
        assertThat(luhnValidator.isValid(firstPan)).isTrue();
        assertThat(luhnValidator.isValid(secondPan)).isTrue();
    }
}
