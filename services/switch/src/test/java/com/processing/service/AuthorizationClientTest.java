package com.processing.service;

import com.processing.Properties;
import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackResponse;
import com.processing.exception.AuthorizationException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit-тесты HTTP-клиента Authorization Service ({@link AuthorizationClient}):
 * валидация ответов, поведение при ошибках и открытом circuit breaker.
 * HTTP-слой изолирован {@link MockRestServiceServer}, retry и CB — реальные экземпляры.
 */
class AuthorizationClientTest {

    private static final String AUTHORIZE_URL = Properties.AUTHORIZATION_URL + "/api/internal/authorize";
    private static final String ROLLBACK_URL = Properties.AUTHORIZATION_URL + "/api/internal/rollback";
    private static final String HEALTH_URL = Properties.AUTHORIZATION_URL + "/health";
    private static final String STAN = "000001";

    private MockRestServiceServer mockServer;
    private CircuitBreaker circuitBreaker;
    private AuthorizationClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        circuitBreaker = CircuitBreaker.ofDefaults("authorization");
        Retry retry = Retry.of("authorization", RetryConfig.custom()
                .maxAttempts(1)
                .failAfterMaxAttempts(true)
                .build());
        client = new AuthorizationClient(Properties.defaults(), builder.build(), retry, circuitBreaker);
    }

    @AfterEach
    void tearDown() {
        mockServer.reset();
    }

    @Test
    void shouldReturnResponseWhenAuthorizationApproves() {
        mockServer.expect(requestTo(AUTHORIZE_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(approvedJson(), MediaType.APPLICATION_JSON));

        AuthorizationResponse response = client.authorize(request());

        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_APPROVED);
        assertThat(response.rrn()).isEqualTo("629712345000");
        mockServer.verify();
    }

    @Test
    void shouldReturnResponseWhenAuthorizationDeclines() {
        mockServer.expect(requestTo(AUTHORIZE_URL))
                .andRespond(withSuccess(declinedJson(), MediaType.APPLICATION_JSON));

        AuthorizationResponse response = client.authorize(request());

        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        assertThat(response.declineReason()).isEqualTo("INSUFFICIENT_FUNDS");
        mockServer.verify();
    }

    @Test
    void shouldThrowAuthorizationExceptionWhenResponseStatusIsUnexpected() {
        mockServer.expect(requestTo(AUTHORIZE_URL))
                .andRespond(withSuccess(unexpectedStatusJson(), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.authorize(request()))
                .isInstanceOf(AuthorizationException.class)
                .hasMessageContaining(STAN)
                .hasMessageContaining("Invalid response from Authorization");
        mockServer.verify();
    }

    @Test
    void shouldThrowAuthorizationExceptionWhenAuthorizationReturnsError() {
        mockServer.expect(requestTo(AUTHORIZE_URL))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.authorize(request()))
                .isInstanceOf(AuthorizationException.class)
                .hasMessageContaining(STAN);
        mockServer.verify();
    }

    @Test
    void shouldThrowAuthorizationExceptionWhenCircuitBreakerIsOpen() {
        circuitBreaker.transitionToOpenState();

        assertThatThrownBy(() -> client.authorize(request()))
                .isInstanceOf(AuthorizationException.class)
                .hasMessageContaining("Circuit breaker open");
    }

    @Test
    void shouldReturnRollbackResponseWhenRollbackSucceeds() {
        mockServer.expect(requestTo(ROLLBACK_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(rollbackApprovedJson(), MediaType.APPLICATION_JSON));

        RollbackResponse response = client.rollback(request(), "629712345000");

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(RollbackResponse.STATUS_APPROVED);
        mockServer.verify();
    }

    @Test
    void shouldReturnNullWhenRollbackResponseIsInvalid() {
        mockServer.expect(requestTo(ROLLBACK_URL))
                .andRespond(withSuccess(rollbackInvalidJson(), MediaType.APPLICATION_JSON));

        RollbackResponse response = client.rollback(request(), "629712345000");

        assertThat(response).isNull();
        mockServer.verify();
    }

    @Test
    void shouldReturnNullWhenRollbackRequestFails() {
        mockServer.expect(requestTo(ROLLBACK_URL))
                .andRespond(withServerError());

        RollbackResponse response = client.rollback(request(), "629712345000");

        assertThat(response).isNull();
        mockServer.verify();
    }

    @Test
    void shouldReturnOkWhenAuthorizationHealthCheckSucceeds() {
        mockServer.expect(requestTo(HEALTH_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess());

        String health = client.checkHealth();

        assertThat(health).isEqualTo("ok");
        mockServer.verify();
    }

    @Test
    void shouldReturnDownWhenAuthorizationHealthCheckFails() {
        mockServer.expect(requestTo(HEALTH_URL))
                .andRespond(withServerError());

        String health = client.checkHealth();

        assertThat(health).isEqualTo("down");
        mockServer.verify();
    }

    private AuthorizationRequest request() {
        return new AuthorizationRequest(
                "0100", STAN, "4000001234567890", "000000",
                new BigDecimal("1000"), "643", Instant.parse("2026-10-26T12:00:00Z"),
                "TERM0001", "POS", "MERCH0000000002", "5045", "ACQ002", "ISS001");
    }

    private String approvedJson() {
        return "{\"mti\":\"0110\",\"stan\":\"000001\",\"rrn\":\"629712345000\",\"authCode\":\"ABC123\","
                + "\"responseCode\":\"00\",\"status\":\"APPROVED\",\"declineReason\":null,\"processingTimeMs\":42}";
    }

    private String declinedJson() {
        return "{\"mti\":\"0110\",\"stan\":\"000001\",\"rrn\":null,\"authCode\":null,"
                + "\"responseCode\":\"51\",\"status\":\"DECLINED\",\"declineReason\":\"INSUFFICIENT_FUNDS\","
                + "\"processingTimeMs\":7}";
    }

    private String unexpectedStatusJson() {
        return "{\"mti\":\"0110\",\"stan\":\"000001\",\"rrn\":null,\"authCode\":null,"
                + "\"responseCode\":\"00\",\"status\":\"WEIRD\",\"declineReason\":null,\"processingTimeMs\":1}";
    }

    private String rollbackApprovedJson() {
        return "{\"rrn\":\"629712345000\",\"responseCode\":\"00\",\"status\":\"APPROVED\","
                + "\"declineReason\":null,\"processingTimeMs\":3}";
    }

    private String rollbackInvalidJson() {
        return "{\"rrn\":\"629712345000\",\"responseCode\":\"00\",\"status\":\"WEIRD\","
                + "\"declineReason\":null,\"processingTimeMs\":3}";
    }
}
