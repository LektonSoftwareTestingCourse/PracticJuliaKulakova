package com.processing.cardmanagement.services;

import com.processing.cardmanagement.events.CardEventNotifier;
import com.processing.cardmanagement.events.CardServiceReserveEvent;
import com.processing.cardmanagement.events.CardServiceRollbackEvent;
import com.processing.cardmanagement.exceptions.CardNotFoundException;
import com.processing.cardmanagement.exceptions.ReservationAlreadyExistsException;
import com.processing.cardmanagement.exceptions.ReservationNotFoundException;
import com.processing.cardmanagement.exceptions.TooLargeLimitException;
import com.processing.cardmanagement.models.Card;
import com.processing.cardmanagement.models.CardStatus;
import com.processing.cardmanagement.models.Reservation;
import com.processing.cardmanagement.options.CardServiceDefaults;
import com.processing.cardmanagement.options.CardServiceSettings;
import com.processing.cardmanagement.repositories.CardRepository;
import com.processing.cardmanagement.repositories.ReservationRepository;
import com.processing.cardmanagement.repositories.ReservationRollbackRepository;
import com.processing.cardmanagement.services.retries.RetryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты {@link CardServiceImpl}: получение карт, пагинация,
 * резервирование и возврат средств. Все зависимости изолированы моками,
 * {@link TransactionRunner} выполняет переданный supplier синхронно.
 */
@ExtendWith(MockitoExtension.class)
class CardServiceImplTest {

    private static final String PAN = "4000001234567893";
    private static final String BIN = "400000";
    private static final String RRN = "629712345000";
    private static final BigDecimal AMOUNT = new BigDecimal("1000");

    @Mock
    private CardRepository cardRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private ReservationRollbackRepository reservationRollbackRepository;

    @Mock
    private CardServiceSettings settings;

    @Mock
    private CardServiceDefaults defaults;

    @Mock
    private PanGenerator panGenerator;

    @Mock
    private CardEventNotifier eventNotifier;

    @Mock
    private BinIssuerService binIssuerService;

    @Mock
    private RetryService retryService;

    @Mock
    private TransactionRunner transactionRunner;

    private CardServiceImpl cardService;

    @BeforeEach
    void setUp() {
        cardService = new CardServiceImpl(
                cardRepository,
                reservationRepository,
                reservationRollbackRepository,
                settings,
                defaults,
                panGenerator,
                eventNotifier,
                binIssuerService,
                retryService,
                transactionRunner);
    }

    @Test
    void shouldReturnCardWhenCardExists() {
        Card card = activeCard();
        when(cardRepository.findByPan(PAN)).thenReturn(Optional.of(card));

        Card found = cardService.getCard(PAN);

        assertThat(found).isSameAs(card);
    }

    @Test
    void shouldThrowCardNotFoundWhenCardDoesNotExist() {
        when(cardRepository.findByPan(PAN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cardService.getCard(PAN))
                .isInstanceOf(CardNotFoundException.class);
    }

    @Test
    void shouldThrowTooLargeLimitWhenRequestedLimitExceedsMaximum() {
        when(settings.maxPageLimit()).thenReturn(100);

        assertThatThrownBy(() -> cardService.getCards(101, null, null, null, null, null, null))
                .isInstanceOf(TooLargeLimitException.class)
                .hasMessageContaining("101");
    }

    @Test
    void shouldUseDefaultPaginationWhenLimitAndOffsetAreNull() {
        when(defaults.pageLimit()).thenReturn(50);
        when(defaults.pageOffset()).thenReturn(0L);
        when(cardRepository.findCards(50, 0L, null, null, null, null, null))
                .thenReturn(List.of(activeCard()));

        List<Card> cards = cardService.getCards(null, null, null, null, null, null, null);

        assertThat(cards).hasSize(1);
        verify(cardRepository).findCards(50, 0L, null, null, null, null, null);
    }

    @Test
    void shouldReserveFundsWhenCardIsActiveAndRrnIsUnique() {
        runSuppliersSynchronously();
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCard()));
        when(reservationRepository.isUnique(RRN, PAN)).thenReturn(true);
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(cardRepository.update(any(Card.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Card result = cardService.reserve(PAN, AMOUNT, RRN);

        assertThat(result.availableBalance()).isEqualByComparingTo("9000");
        verify(eventNotifier).notifyListeners(any(CardServiceReserveEvent.class));
    }

    @Test
    void shouldThrowReservationAlreadyExistsWhenRrnIsNotUnique() {
        runSuppliersSynchronously();
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCard()));
        when(reservationRepository.isUnique(RRN, PAN)).thenReturn(false);

        assertThatThrownBy(() -> cardService.reserve(PAN, AMOUNT, RRN))
                .isInstanceOf(ReservationAlreadyExistsException.class)
                .hasMessageContaining(RRN);
    }

    @Test
    void shouldReturnFundsWhenReservationExists() {
        runSuppliersSynchronously();
        Reservation reservation = new Reservation(PAN, AMOUNT, RRN);
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCard()));
        when(reservationRepository.findByRrnAndPanForUpdate(RRN, PAN)).thenReturn(Optional.of(reservation));
        when(reservationRollbackRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(cardRepository.update(any(Card.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Card result = cardService.rollback(PAN, AMOUNT, RRN);

        assertThat(result.availableBalance()).isEqualByComparingTo("11000");
        verify(eventNotifier).notifyListeners(any(CardServiceRollbackEvent.class));
    }

    @Test
    void shouldThrowReservationNotFoundWhenRollingBackUnknownReservation() {
        runSuppliersSynchronously();
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCard()));
        when(reservationRepository.findByRrnAndPanForUpdate(RRN, PAN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cardService.rollback(PAN, AMOUNT, RRN))
                .isInstanceOf(ReservationNotFoundException.class)
                .hasMessageContaining(RRN);
    }

    @Test
    void shouldThrowIllegalArgumentWhenBulkUpdateReceivesNeitherBinsNorPans() {
        assertThatThrownBy(() -> cardService.bulkUpdateStatus(null, null, CardStatus.BLOCKED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Either bin or pans must be provided");
    }

    @Test
    void shouldThrowIllegalArgumentWhenBulkUpdateReceivesBothBinsAndPans() {
        assertThatThrownBy(() -> cardService.bulkUpdateStatus(List.of(BIN), List.of(PAN), CardStatus.BLOCKED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only one of bin or pans must be provided");
    }

    @Test
    void shouldUpdateStatusForEveryCardFoundByBins() {
        runSuppliersSynchronously();
        when(cardRepository.findCardsByBinsForUpdate(List.of(BIN)))
                .thenReturn(List.of(activeCard(), activeCard()));
        when(cardRepository.update(any(Card.class))).thenAnswer(invocation -> invocation.getArgument(0));

        int updated = cardService.bulkUpdateStatus(List.of(BIN), null, CardStatus.BLOCKED);

        assertThat(updated).isEqualTo(2);
        verify(cardRepository, times(2)).update(any(Card.class));
    }

    private void runSuppliersSynchronously() {
        when(transactionRunner.runSupplier(any())).thenAnswer(invocation -> {
            Supplier<?> supplier = invocation.getArgument(0);
            return supplier.get();
        });
    }

    private Card activeCard() {
        return new Card(
                UUID.randomUUID(), PAN, BIN, "IVAN IVANOV",
                YearMonth.of(2027, 12), CardStatus.ACTIVE, "643",
                new BigDecimal("100000"), new BigDecimal("500000"), new BigDecimal("10000"),
                "ISS001", Instant.parse("2026-01-01T00:00:00Z"));
    }
}
