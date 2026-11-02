package com.processing.authorization.services;

import com.processing.authorization.client.BinLookupClient;
import com.processing.authorization.client.CardManagementClient;
import com.processing.authorization.events.AuthorizationEvent;
import com.processing.authorization.events.AuthorizationEventNotifier;
import com.processing.authorization.exceptions.CardNotFoundException;
import com.processing.authorization.exceptions.GetCardException;
import com.processing.authorization.exceptions.InsufficientFundsException;
import com.processing.authorization.exceptions.InternalCardManagerException;
import com.processing.authorization.exceptions.InvalidReserveRequestException;
import com.processing.authorization.exceptions.InvalidRollbackRequestException;
import com.processing.authorization.exceptions.PaymentRequiredException;
import com.processing.authorization.exceptions.ReserveException;
import com.processing.authorization.exceptions.RollbackConflictException;
import com.processing.authorization.exceptions.RollbackFailureException;
import com.processing.authorization.exceptions.ServiceUnavailableException;
import com.processing.authorization.repositories.LimitUsageRepository;
import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackRequest;
import com.processing.common.dto.authorization.RollbackResponse;
import com.processing.common.dto.cardmanagement.CardModel;
import com.processing.common.dto.cardmanagement.CardModelStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты бизнес-логики {@link AuthServiceImpl}.
 *
 * <p>Зависимости изолированы моками (Mockito), объект сервиса создаётся явно
 * в {@code @BeforeEach} Каждый тест следует AAA и проверяет одно поведение.</p>
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    private static final String PAN = "4000001234567890";
    private static final String BIN = "400000";
    private static final String ISSUER_ID = "ISS001";
    private static final String RRN = "629712345000";
    private static final BigDecimal AMOUNT = new BigDecimal("1000");
    private static final LocalDate TRANSMISSION_DATE = LocalDate.of(2026, 10, 26);
    private static final Instant REQUEST_TIME = Instant.now();

    @Mock
    private LimitUsageRepository limitUsageRepository;

    @Mock
    private AuthorizationEventNotifier eventNotifier;

    @Mock
    private BinLookupClient binLookupClient;

    @Mock
    private CardManagementClient cardManagementClient;

    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        authService = new AuthServiceImpl(
                limitUsageRepository,
                eventNotifier,
                binLookupClient,
                cardManagementClient);
    }

    @Test
    void shouldApproveAuthorizationWhenCardIsActiveAndSufficientFunds() {
        CardModel card = activeCard();
        when(cardManagementClient.getCard(PAN)).thenReturn(card);
        when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.of(ISSUER_ID));
        when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(1);

        AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_APPROVED);
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_APPROVED);
        assertThat(response.rrn()).isNotBlank().matches("\\d{12}");
        assertThat(response.authCode()).isNotBlank().matches("[0-9A-Z]{6}");
        verify(cardManagementClient).reserve(any(BigDecimal.class), any(String.class), any(String.class));
        verify(eventNotifier).notify(any(AuthorizationEvent.class));
    }

    @Test
    void shouldApproveAuthorizationWhenBinLookupReturnsEmptyIssuerId() {
        when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());
        when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.empty());
        when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(1);

        AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_APPROVED);
        verify(eventNotifier).notify(any(AuthorizationEvent.class));
    }

    @Test
    void shouldDeclineWhenCardNotFound() {
        when(cardManagementClient.getCard(PAN)).thenThrow(new CardNotFoundException(PAN));

        AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        assertThat(response.declineReason()).isEqualTo("CARD_NOT_FOUND");
        verify(eventNotifier).notify(any(AuthorizationEvent.class));
    }

    @ParameterizedTest(name = "getCard throws {0}")
    @MethodSource("cardManagementUnavailableExceptions")
    void shouldDeclineWithServiceUnavailableWhenCardManagementIsNotReachable(RuntimeException exception) {
        when(cardManagementClient.getCard(PAN)).thenThrow(exception);

        AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        assertThat(response.declineReason()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_SERVICE_UNAVAILABLE);
        verify(eventNotifier).notify(any(AuthorizationEvent.class));
    }

    @Test
    void shouldDeclineWithCardBlockedWhenCardManagementRequiresPayment() {
        when(cardManagementClient.getCard(PAN)).thenThrow(new PaymentRequiredException("payment required"));

        AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        assertThat(response.declineReason()).isEqualTo("CARD_BLOCKED");
        verify(eventNotifier).notify(any(AuthorizationEvent.class));
    }

    @Test
    void shouldDeclineWithServiceUnavailableWhenGetCardFailsWithGenericError() {
        when(cardManagementClient.getCard(PAN)).thenThrow(new GetCardException("unexpected http status"));

        AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        assertThat(response.declineReason()).isEqualTo("SERVICE_UNAVAILABLE");
        verify(eventNotifier).notify(any(AuthorizationEvent.class));
    }

    @Test
    void shouldDeclineWithUnknownReasonWhenGetCardThrowsUnexpectedException() {
        when(cardManagementClient.getCard(PAN)).thenThrow(new IllegalStateException("boom"));

        AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        assertThat(response.declineReason()).isEqualTo("UNKNOWN_REASON");
        verify(eventNotifier).notify(any(AuthorizationEvent.class));
    }

    @ParameterizedTest(name = "status={0}")
    @EnumSource(value = CardModelStatus.class, mode = EnumSource.Mode.EXCLUDE, names = {"ACTIVE"})
    void shouldDeclineWhenCardStatusIsNotActive(CardModelStatus status) {
        CardModel card = cardWithStatus(status);
        when(cardManagementClient.getCard(PAN)).thenReturn(card);
        when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.of(ISSUER_ID));

        AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        verify(eventNotifier).notify(any(AuthorizationEvent.class));
    }

    @ParameterizedTest(name = "status={0} -> reason={1}")
    @CsvSource({
            "INACTIVE, CARD_INACTIVE",
            "BLOCKED,  CARD_BLOCKED",
            "EXPIRED,  CARD_EXPIRED"
    })
    void shouldDeclineWithStatusSpecificReasonWhenCardStatusIsNotActive(
            CardModelStatus status, String expectedReason) {
        when(cardManagementClient.getCard(PAN)).thenReturn(cardWithStatus(status));
        when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.of(ISSUER_ID));

        AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        assertThat(response.declineReason()).isEqualTo(expectedReason);
    }

    @Test
    void shouldDeclineWithUnknownReasonWhenCardStatusIsNull() {
        when(cardManagementClient.getCard(PAN)).thenReturn(cardWithStatus(null));
        when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.of(ISSUER_ID));

        AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        assertThat(response.declineReason()).isEqualTo("UNKNOWN_REASON");
        verify(eventNotifier).notify(any(AuthorizationEvent.class));
    }

    @Test
    void shouldDeclineWithCardExpiredWhenExpiryDateIsBeforeTransmissionDate() {
        CardModel expiredCard = cardWithExpiry(YearMonth.of(2020, 1));
        when(cardManagementClient.getCard(PAN)).thenReturn(expiredCard);
        when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.of(ISSUER_ID));

        AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        assertThat(response.declineReason()).isEqualTo("CARD_EXPIRED");
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_CARD_EXPIRED);
        verify(eventNotifier).notify(any(AuthorizationEvent.class));
    }

    @Test
    void shouldDeclineWithInsufficientFundsWhenAmountExceedsAvailableBalance() {
        when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());
        when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.of(ISSUER_ID));
        AuthorizationRequest request = requestWithAmount(new BigDecimal("99999999"));

        AuthorizationResponse response = authService.authorize(request, REQUEST_TIME);

        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        assertThat(response.declineReason()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_INSUFFICIENT_FUNDS);
        verify(eventNotifier).notify(any(AuthorizationEvent.class));
    }

    @Test
    void shouldDeclineWithExceedsAmountLimitWhenLimitUpsertAffectsNoRows() {
        when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());
        when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.of(ISSUER_ID));
        when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(0);

        AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        assertThat(response.declineReason()).isEqualTo("EXCEEDS_AMOUNT_LIMIT");
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_EXCEEDS_LIMIT);
        verify(eventNotifier).notify(any(AuthorizationEvent.class));
    }

    @Test
    void shouldApproveAuthorizationWhenLimitUpsertSucceedsAfterDuplicateKeyRetry() {
        when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());
        when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.of(ISSUER_ID));
        when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any()))
                .thenThrow(new DuplicateKeyException("concurrent insert"))
                .thenReturn(1);

        AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_APPROVED);
        verify(limitUsageRepository, times(2)).upsertLimitUsage(any(), any(), any(), any(), any());
        verify(eventNotifier, times(2)).notify(any(AuthorizationEvent.class));
    }

    @Test
    void shouldDeclineWithDbUnavailableWhenLimitUpsertFails() {
        when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());
        when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.of(ISSUER_ID));
        when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("database is down"));

        AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        assertThat(response.declineReason()).isEqualTo("DB_UNAVAILABLE");
        verify(eventNotifier).notify(any(AuthorizationEvent.class));
    }

    @Test
    void shouldDeclineWithDbUnavailableWhenLimitRetryFailsAgain() {
        when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());
        when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.of(ISSUER_ID));
        when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any()))
                .thenThrow(new DuplicateKeyException("concurrent insert"))
                .thenThrow(new IllegalStateException("database is down"));

        AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        assertThat(response.declineReason()).isEqualTo("DB_UNAVAILABLE");
        verify(limitUsageRepository, times(2)).upsertLimitUsage(any(), any(), any(), any(), any());
    }

    @ParameterizedTest(name = "reserve throws {0} -> reason={1}")
    @MethodSource("reserveFailures")
    void shouldDeclineWithMappedReasonWhenReserveFails(
            RuntimeException reserveFailure, String expectedReason) {
        when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());
        when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.of(ISSUER_ID));
        when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(1);
        doThrow(reserveFailure).when(cardManagementClient)
                .reserve(any(BigDecimal.class), any(String.class), any(String.class));

        AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        assertThat(response.declineReason()).isEqualTo(expectedReason);
        verify(eventNotifier).notify(any(AuthorizationEvent.class));
    }

    @Test
    void shouldApproveRollbackWhenCardManagementRollbackSucceeds() {
        RollbackResponse response = authService.rollback(rollbackRequest(), REQUEST_TIME);

        assertThat(response.status()).isEqualTo(RollbackResponse.STATUS_APPROVED);
        assertThat(response.rrn()).isEqualTo(RRN);
        assertThat(response.responseCode()).isEqualTo(RollbackResponse.CODE_SUCCESS);
        verify(cardManagementClient).rollback(rollbackRequest());
        verify(eventNotifier).notify(any(AuthorizationEvent.class));
    }

    @ParameterizedTest(name = "rollback throws {0} -> reason={1}")
    @MethodSource("rollbackFailures")
    void shouldDeclineRollbackWithMappedReasonWhenRollbackFails(
            RuntimeException rollbackFailure, String expectedReason) {
        doThrow(rollbackFailure).when(cardManagementClient).rollback(any(RollbackRequest.class));

        RollbackResponse response = authService.rollback(rollbackRequest(), REQUEST_TIME);

        assertThat(response.status()).isEqualTo(RollbackResponse.STATUS_DECLINED);
        assertThat(response.declineReason()).isEqualTo(expectedReason);
        verify(eventNotifier).notify(any(AuthorizationEvent.class));
    }

    @ParameterizedTest(name = "upsert affected {0} rows -> {1}")
    @CsvSource({"1, true", "0, false"})
    void shouldReturnLimitUpdateResultBasedOnAffectedRows(int affectedRows, boolean expected) {
        when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(affectedRows);

        boolean updated = authService.checkAndUpdateLimits(activeCard(), AMOUNT, TRANSMISSION_DATE);

        assertThat(updated).isEqualTo(expected);
    }

    @Test
    void shouldGenerateSequentialTwelveDigitRrnFromRepositoryBlock() {
        when(limitUsageRepository.fetchRrnBlock()).thenReturn(12345L);

        String firstRrn = authService.generateRRN();
        String secondRrn = authService.generateRRN();

        assertThat(firstRrn).matches("\\d{12}").endsWith("12345000");
        assertThat(secondRrn).matches("\\d{12}").endsWith("12345001");
        verify(limitUsageRepository, times(1)).fetchRrnBlock();
    }

    @Test
    void shouldGenerateSixCharacterAlphanumericAuthCode() {
        String authCode = authService.generateAuthCode();

        assertThat(authCode).hasSize(6).matches("[0-9A-Z]{6}");
    }

    private static Stream<Arguments> cardManagementUnavailableExceptions() {
        return Stream.of(
                Arguments.of(new ServiceUnavailableException("cms is down")),
                Arguments.of(new ResourceAccessException("connection timeout")),
                Arguments.of(new InternalCardManagerException("internal error")));
    }

    private static Stream<Arguments> reserveFailures() {
        return Stream.of(
                Arguments.of(new CardNotFoundException(PAN), "CARD_NOT_FOUND"),
                Arguments.of(new ServiceUnavailableException("cms is down"), "SERVICE_UNAVAILABLE"),
                Arguments.of(new ResourceAccessException("connection timeout"), "SERVICE_UNAVAILABLE"),
                Arguments.of(new InternalCardManagerException("internal error"), "SERVICE_UNAVAILABLE"),
                Arguments.of(new InvalidReserveRequestException("bad request"), "CARD_NOT_FOUND"),
                Arguments.of(new InsufficientFundsException("no funds"), "INSUFFICIENT_FUNDS"),
                Arguments.of(new ReserveException("reservation error"), "RESERVATION_FAILED"),
                Arguments.of(new IllegalStateException("boom"), "UNKNOWN_REASON"));
    }

    private static Stream<Arguments> rollbackFailures() {
        return Stream.of(
                Arguments.of(new CardNotFoundException(PAN), "TRANSACTION_NOT_FOUND"),
                Arguments.of(new InvalidRollbackRequestException("bad request"), "TRANSACTION_NOT_FOUND"),
                Arguments.of(new ServiceUnavailableException("cms is down"), "SERVICE_UNAVAILABLE"),
                Arguments.of(new ResourceAccessException("connection timeout"), "SERVICE_UNAVAILABLE"),
                Arguments.of(new InternalCardManagerException("internal error"), "SERVICE_UNAVAILABLE"),
                Arguments.of(new RollbackConflictException("already rolled back"), "ALREADY_ROLLED_BACK"),
                Arguments.of(new RollbackFailureException("rollback error"), "ROLLBACK_FAILED"),
                Arguments.of(new IllegalStateException("boom"), "UNKNOWN_REASON"));
    }

    private AuthorizationRequest validRequest() {
        return requestWithAmount(AMOUNT);
    }

    private AuthorizationRequest requestWithAmount(BigDecimal amount) {
        return new AuthorizationRequest(
                "0100", "000001", PAN, "000000",
                amount, "643", REQUEST_TIME,
                "TERM0001", "POS", "MERCH0000000002", "5045", "ACQ002", ISSUER_ID);
    }

    private RollbackRequest rollbackRequest() {
        return new RollbackRequest(RRN, PAN, AMOUNT);
    }

    private CardModel activeCard() {
        return cardWithStatus(CardModelStatus.ACTIVE);
    }

    private CardModel cardWithStatus(CardModelStatus status) {
        return cardWithExpiry(status, YearMonth.of(2027, 12));
    }

    private CardModel cardWithExpiry(YearMonth expiryDate) {
        return cardWithExpiry(CardModelStatus.ACTIVE, expiryDate);
    }

    private CardModel cardWithExpiry(CardModelStatus status, YearMonth expiryDate) {
        return new CardModel(
                UUID.randomUUID(), PAN, BIN, "IVAN IVANOV",
                expiryDate, status, "643",
                new BigDecimal("100000"), new BigDecimal("500000"), new BigDecimal("10000"),
                ISSUER_ID, Instant.now());
    }
}
