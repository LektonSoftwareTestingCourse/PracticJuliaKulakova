package com.processing.controller;

import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.service.RouteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты REST-эндпоинта маршрутизации ({@link RouteController}), включая маскирование PAN в логах.
 */
@ExtendWith(MockitoExtension.class)
class RouteControllerTest {

    private static final String PAN = "4000001234567890";

    @Mock
    private RouteService routeService;

    private RouteController routeController;

    @BeforeEach
    void setUp() {
        routeController = new RouteController(routeService);
    }

    @Test
    void shouldReturnOkWithApprovedResponseWhenRouteSucceeds() {
        AuthorizationResponse approved = approvedResponse();
        when(routeService.route(any(AuthorizationRequest.class))).thenReturn(approved);

        ResponseEntity<AuthorizationResponse> entity = routeController.routeTransaction(requestWithPan(PAN));

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(entity.getBody()).isSameAs(approved);
        verify(routeService).route(any(AuthorizationRequest.class));
    }

    @ParameterizedTest(name = "pan='{0}'")
    @MethodSource("shortPans")
    void shouldNotFailWhenPanIsShorterThanEightCharacters(String pan) {
        when(routeService.route(any(AuthorizationRequest.class))).thenReturn(approvedResponse());

        ResponseEntity<AuthorizationResponse> entity = routeController.routeTransaction(requestWithPan(pan));

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private static Stream<Arguments> shortPans() {
        return Stream.of(
                Arguments.of((String) null),
                Arguments.of(""),
                Arguments.of("1234567"));
    }

    private AuthorizationRequest requestWithPan(String pan) {
        return new AuthorizationRequest(
                "0100", "000001", pan, "000000",
                new BigDecimal("1000"), "643", Instant.parse("2026-10-26T12:00:00Z"),
                "TERM0001", "POS", "MERCH0000000002", "5045", "ACQ002", "ISS001");
    }

    private AuthorizationResponse approvedResponse() {
        return new AuthorizationResponse(
                "0110", "000001", "629712345000", "ABC123", "00", "APPROVED", null, 42);
    }
}
