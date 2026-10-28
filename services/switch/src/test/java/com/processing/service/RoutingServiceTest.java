package com.processing.service;

import com.processing.Properties;
import com.processing.exception.UnknownBinException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit-тесты маршрутизации BIN → issuerId ({@link RoutingService}).
 */
class RoutingServiceTest {

    private static final String PAN = "4000001234567890";

    private RoutingService routingService;

    @BeforeEach
    void setUp() {
        com.processing.config.SwitchProperties properties = Properties.withBinRouting(Map.of(
                "400000", "ISS001",
                "524151", "ISS002"));
        routingService = new RoutingService(properties);
    }

    @Test
    void shouldReturnIssuerIdWhenBinIsKnown() {
        String issuerId = routingService.getIssuerIdByPan(PAN);

        assertThat(issuerId).isEqualTo("ISS001");
    }

    @Test
    void shouldReturnIssuerIdWhenPanIsExactlySixCharacters() {
        String issuerId = routingService.getIssuerIdByPan("400000");

        assertThat(issuerId).isEqualTo("ISS001");
    }

    @Test
    void shouldThrowUnknownBinWhenPanIsNull() {
        assertThatThrownBy(() -> routingService.getIssuerIdByPan(null))
                .isInstanceOf(UnknownBinException.class)
                .hasMessageContaining("null-or-short");
    }

    @ParameterizedTest(name = "pan='{0}'")
    @ValueSource(strings = {"", "12345"})
    void shouldThrowUnknownBinWhenPanIsShorterThanSixCharacters(String pan) {
        assertThatThrownBy(() -> routingService.getIssuerIdByPan(pan))
                .isInstanceOf(UnknownBinException.class)
                .hasMessageContaining("null-or-short");
    }

    @Test
    void shouldThrowUnknownBinWhenBinIsNotInRoutingTable() {
        assertThatThrownBy(() -> routingService.getIssuerIdByPan("9999991234567890"))
                .isInstanceOf(UnknownBinException.class)
                .hasMessageContaining("999999");
    }
}
