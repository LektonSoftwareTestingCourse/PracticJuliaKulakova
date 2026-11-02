package com.processing.controller;

import com.processing.Properties;
import com.processing.dto.HealthResponse;
import com.processing.service.AuthorizationClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты health-check эндпоинта Switch ({@link HealthController}).
 */
@ExtendWith(MockitoExtension.class)
class HealthControllerTest {

    @Mock
    private AuthorizationClient authorizationClient;

    private HealthController healthController;

    @BeforeEach
    void setUp() {
        healthController = new HealthController(Properties.defaults(), authorizationClient);
    }

    @Test
    void shouldReportOkStatusWithAuthorizationDependencyWhenAuthorizationIsUp() {
        when(authorizationClient.checkHealth()).thenReturn("ok");

        ResponseEntity<HealthResponse> entity = healthController.health();

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.OK);
        HealthResponse body = entity.getBody();
        assertThat(body.status()).isEqualTo("ok");
        assertThat(body.service()).isEqualTo("switch");
        assertThat(body.version()).isEqualTo("1.0.0");
        assertThat(body.dependencies()).containsEntry("authorization", "ok");
    }

    @Test
    void shouldReportDownDependencyWhenAuthorizationIsUnavailable() {
        when(authorizationClient.checkHealth()).thenReturn("down");

        ResponseEntity<HealthResponse> entity = healthController.health();

        assertThat(entity.getBody().dependencies()).containsEntry("authorization", "down");
    }
}
