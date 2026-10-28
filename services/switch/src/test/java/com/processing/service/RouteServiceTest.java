package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackResponse;
import com.processing.common.dto.transactionlogger.TransactionRequest;
import com.processing.common.dto.transactionlogger.TransactionStatus;
import com.processing.exception.AuthorizationException;
import com.processing.exception.UnknownBinException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты оркестратора маршрутизации {@link RouteService}:
 * BIN-маршрутизация, авторизация, публикация в Logger и rollback при сбое очереди.
 */
@ExtendWith(MockitoExtension.class)
class RouteServiceTest {

    private static final String PAN = "4000001234567890";
    private static final String STAN = "000001";
    private static final String ISSUER_ID = "ISS001";
    private static final String RRN = "629712345000";
    private static final Instant TRANSMISSION_TIME = Instant.parse("2026-10-26T12:00:00Z");

    @Mock
    private RoutingService routingService;

    @Mock
    private AuthorizationClient authorizationClient;

    @Mock
    private AcquiringFeeClient acquiringFeeClient;

    @Mock
    private LoggerClient loggerClient;

    private RouteService routeService;

    @BeforeEach
    void setUp() {
        routeService = new RouteService(routingService, authorizationClient, acquiringFeeClient, loggerClient);
    }

    @Test
    void shouldReturnApprovedResponseWhenTransactionIsRoutedAndLogged() {
        AuthorizationResponse approved = approvedResponse();
        when(routingService.getIssuerIdByPan(PAN)).thenReturn(ISSUER_ID);
        when(authorizationClient.authorize(any(AuthorizationRequest.class))).thenReturn(approved);
        when(acquiringFeeClient.fetchAcquiringFee(any(), any(), any(), any(), any()))
                .thenReturn(new BigDecimal("12.50"));
        when(loggerClient.log(any(TransactionRequest.class))).thenReturn(true);

        AuthorizationResponse response = routeService.route(validRequest());

        assertThat(response).isSameAs(approved);
        ArgumentCaptor<AuthorizationRequest> routedCaptor = ArgumentCaptor.forClass(AuthorizationRequest.class);
        verify(authorizationClient).authorize(routedCaptor.capture());
        assertThat(routedCaptor.getValue().issuerId()).isEqualTo(ISSUER_ID);
        assertThat(routedCaptor.getValue().terminalId()).isEqualTo("TERM1000");
        ArgumentCaptor<TransactionRequest> loggedCaptor = ArgumentCaptor.forClass(TransactionRequest.class);
        verify(loggerClient).log(loggedCaptor.capture());
        TransactionRequest logged = loggedCaptor.getValue();
        assertThat(logged.status()).isEqualTo(TransactionStatus.APPROVED);
        assertThat(logged.issuerId()).isEqualTo(ISSUER_ID);
        assertThat(logged.rrn()).isEqualTo(RRN);
        assertThat(logged.acquiringFee()).isEqualByComparingTo("12.50");
        verify(authorizationClient, never()).rollback(any(), any());
    }

    @Test
    void shouldDeclineWithCardNotFoundWhenBinIsUnknown() {
        when(routingService.getIssuerIdByPan(PAN)).thenThrow(new UnknownBinException("400000"));
        when(loggerClient.log(any(TransactionRequest.class))).thenReturn(true);

        AuthorizationResponse response = routeService.route(validRequest());

        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_CARD_NOT_FOUND);
        assertThat(response.declineReason()).isEqualTo("CARD_NOT_FOUND");
        verify(authorizationClient, never()).authorize(any());
    }

    @Test
    void shouldDeclineWithGeneralCodeWhenAuthorizationServiceIsUnavailable() {
        when(routingService.getIssuerIdByPan(PAN)).thenReturn(ISSUER_ID);
        when(authorizationClient.authorize(any(AuthorizationRequest.class)))
                .thenThrow(new AuthorizationException(STAN, 3, "connection timeout"));
        when(loggerClient.log(any(TransactionRequest.class))).thenReturn(true);

        AuthorizationResponse response = routeService.route(validRequest());

        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_DECLINED_GENERAL);
        assertThat(response.declineReason()).isEqualTo("Authorization service unavailable");
    }

    @Test
    void shouldRollbackReservationWhenQueuePublishFailsAfterApproval() {
        when(routingService.getIssuerIdByPan(PAN)).thenReturn(ISSUER_ID);
        when(authorizationClient.authorize(any(AuthorizationRequest.class))).thenReturn(approvedResponse());
        when(acquiringFeeClient.fetchAcquiringFee(any(), any(), any(), any(), any())).thenReturn(null);
        when(loggerClient.log(any(TransactionRequest.class))).thenReturn(false);
        when(authorizationClient.rollback(any(AuthorizationRequest.class), eq(RRN))).thenReturn(approvedRollback());

        AuthorizationResponse response = routeService.route(validRequest());

        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_SERVICE_UNAVAILABLE);
        assertThat(response.declineReason()).isEqualTo("System error");
        verify(authorizationClient).rollback(any(AuthorizationRequest.class), eq(RRN));
    }

    @Test
    void shouldReturnSystemErrorWhenRollbackResponseIsNull() {
        when(routingService.getIssuerIdByPan(PAN)).thenReturn(ISSUER_ID);
        when(authorizationClient.authorize(any(AuthorizationRequest.class))).thenReturn(approvedResponse());
        when(acquiringFeeClient.fetchAcquiringFee(any(), any(), any(), any(), any())).thenReturn(null);
        when(loggerClient.log(any(TransactionRequest.class))).thenReturn(false);
        when(authorizationClient.rollback(any(AuthorizationRequest.class), eq(RRN))).thenReturn(null);

        AuthorizationResponse response = routeService.route(validRequest());

        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_SERVICE_UNAVAILABLE);
        assertThat(response.declineReason()).isEqualTo("System error");
    }

    @Test
    void shouldReturnSystemErrorWhenRollbackIsDeclined() {
        when(routingService.getIssuerIdByPan(PAN)).thenReturn(ISSUER_ID);
        when(authorizationClient.authorize(any(AuthorizationRequest.class))).thenReturn(approvedResponse());
        when(acquiringFeeClient.fetchAcquiringFee(any(), any(), any(), any(), any())).thenReturn(null);
        when(loggerClient.log(any(TransactionRequest.class))).thenReturn(false);
        when(authorizationClient.rollback(any(AuthorizationRequest.class), eq(RRN))).thenReturn(declinedRollback());

        AuthorizationResponse response = routeService.route(validRequest());

        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_SERVICE_UNAVAILABLE);
        assertThat(response.declineReason()).isEqualTo("System error");
    }

    @Test
    void shouldKeepDeclinedResponseWhenQueuePublishFailsForDeclinedTransaction() {
        AuthorizationResponse declined = declinedResponse();
        when(routingService.getIssuerIdByPan(PAN)).thenReturn(ISSUER_ID);
        when(authorizationClient.authorize(any(AuthorizationRequest.class))).thenReturn(declined);
        when(acquiringFeeClient.fetchAcquiringFee(any(), any(), any(), any(), any())).thenReturn(null);
        when(loggerClient.log(any(TransactionRequest.class))).thenReturn(false);

        AuthorizationResponse response = routeService.route(validRequest());

        assertThat(response).isSameAs(declined);
        verify(authorizationClient, never()).rollback(any(), any());
    }

    @Test
    void shouldLogZeroProcessingTimeWhenResponseProcessingTimeIsNull() {
        when(routingService.getIssuerIdByPan(PAN)).thenReturn(ISSUER_ID);
        when(authorizationClient.authorize(any(AuthorizationRequest.class)))
                .thenReturn(approvedResponseWithoutProcessingTime());
        when(acquiringFeeClient.fetchAcquiringFee(any(), any(), any(), any(), any())).thenReturn(null);
        when(loggerClient.log(any(TransactionRequest.class))).thenReturn(true);

        routeService.route(validRequest());

        ArgumentCaptor<TransactionRequest> captor = ArgumentCaptor.forClass(TransactionRequest.class);
        verify(loggerClient).log(captor.capture());
        assertThat(captor.getValue().processingTimeMs()).isZero();
    }

    @Test
    void shouldDeclineWithCardNotFoundWhenPanIsNull() {
        when(routingService.getIssuerIdByPan(null)).thenThrow(new UnknownBinException("null-or-short"));
        when(loggerClient.log(any(TransactionRequest.class))).thenReturn(true);

        AuthorizationResponse response = routeService.route(requestWithPan(null));

        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_CARD_NOT_FOUND);
    }

    @Test
    void shouldDeclineWithCardNotFoundWhenPanIsShorterThanSixCharacters() {
        when(routingService.getIssuerIdByPan("12345")).thenThrow(new UnknownBinException("null-or-short"));
        when(loggerClient.log(any(TransactionRequest.class))).thenReturn(true);

        AuthorizationResponse response = routeService.route(requestWithPan("12345"));

        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_CARD_NOT_FOUND);
    }

    private AuthorizationRequest validRequest() {
        return requestWithPan(PAN);
    }

    private AuthorizationRequest requestWithPan(String pan) {
        return new AuthorizationRequest(
                "0100", STAN, pan, "000000",
                new BigDecimal("1000"), "643", TRANSMISSION_TIME,
                "TERM1", "POS", "MERCH1", "5045", "ACQ002", null);
    }

    private AuthorizationResponse approvedResponse() {
        return new AuthorizationResponse("0110", STAN, RRN, "ABC123", "00", "APPROVED", null, 42);
    }

    private AuthorizationResponse approvedResponseWithoutProcessingTime() {
        return new AuthorizationResponse("0110", STAN, RRN, "ABC123", "00", "APPROVED", null, null);
    }

    private AuthorizationResponse declinedResponse() {
        return new AuthorizationResponse("0110", STAN, null, null, "51", "DECLINED", "INSUFFICIENT_FUNDS", 7);
    }

    private RollbackResponse approvedRollback() {
        return new RollbackResponse(RRN, "00", "APPROVED", null, 5);
    }

    private RollbackResponse declinedRollback() {
        return new RollbackResponse(RRN, "05", "DECLINED", "TRANSACTION_NOT_FOUND", 5);
    }
}
