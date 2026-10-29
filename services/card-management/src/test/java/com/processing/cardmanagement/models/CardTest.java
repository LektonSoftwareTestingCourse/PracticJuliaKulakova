package com.processing.cardmanagement.models;

import com.processing.cardmanagement.exceptions.InsufficientFundsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit-тесты доменной модели {@link Card}: резервирование, возврат средств,
 * удаление и изменение данных карты.
 */
class CardTest {

    private static final String PAN = "4000001234567893";
    private static final String BIN = "400000";
    private static final String RRN = "629712345000";
    private static final String OTHER_PAN = "5555555555554444";
    private static final BigDecimal BALANCE = new BigDecimal("10000");
    private static final BigDecimal AMOUNT = new BigDecimal("1000");

    @Test
    void shouldCreateReservationWhenCardIsActiveAndFundsAreSufficient() {
        Card card = activeCard();

        Reservation reservation = card.startReservation(AMOUNT, RRN);

        assertThat(reservation.pan()).isEqualTo(PAN);
        assertThat(reservation.reservationAmount()).isEqualByComparingTo(AMOUNT);
        assertThat(reservation.rrn()).isEqualTo(RRN);
        assertThat(reservation.status()).isEqualTo(ReservationStatus.RESERVED);
    }

    @Test
    void shouldCreateReservationWhenAmountEqualsAvailableBalance() {
        Card card = activeCard();

        Reservation reservation = card.startReservation(BALANCE, RRN);

        assertThat(reservation.reservationAmount()).isEqualByComparingTo(BALANCE);
    }

    @Test
    void shouldThrowInsufficientFundsWhenAmountExceedsAvailableBalance() {
        Card card = activeCard();

        assertThatThrownBy(() -> card.startReservation(BALANCE.add(BigDecimal.ONE), RRN))
                .isInstanceOf(InsufficientFundsException.class);
    }

    @ParameterizedTest(name = "status={0}")
    @EnumSource(value = CardStatus.class, mode = EnumSource.Mode.EXCLUDE, names = {"ACTIVE"})
    void shouldThrowIllegalStateWhenCardStatusIsNotActive(CardStatus status) {
        Card card = cardWith(status, BALANCE);

        assertThatThrownBy(() -> card.startReservation(AMOUNT, RRN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(status.name());
    }

    @Test
    void shouldDecreaseAvailableBalanceWhenReservationIsApplied() {
        Card card = activeCard();
        Reservation reservation = new Reservation(PAN, AMOUNT, RRN);

        Card updated = card.withReservation(reservation);

        assertThat(updated.availableBalance()).isEqualByComparingTo("9000");
        assertThat(updated.id()).isEqualTo(card.id());
        assertThat(updated.pan()).isEqualTo(PAN);
    }

    @Test
    void shouldThrowIllegalArgumentWhenReservationPanDiffersFromCardPan() {
        Card card = activeCard();
        Reservation reservation = new Reservation(OTHER_PAN, AMOUNT, RRN);

        assertThatThrownBy(() -> card.withReservation(reservation))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldIncreaseAvailableBalanceWhenRollbackIsApplied() {
        Card card = activeCard();
        ReservationRollback rollback =
                new ReservationRollback(UUID.randomUUID(), UUID.randomUUID(), PAN, AMOUNT, RRN, Instant.now());

        Card updated = card.withRollback(rollback);

        assertThat(updated.availableBalance()).isEqualByComparingTo("11000");
    }

    @Test
    void shouldThrowIllegalArgumentWhenRollbackPanDiffersFromCardPan() {
        Card card = activeCard();
        ReservationRollback rollback =
                new ReservationRollback(UUID.randomUUID(), UUID.randomUUID(), OTHER_PAN, AMOUNT, RRN, Instant.now());

        assertThatThrownBy(() -> card.withRollback(rollback))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldMarkCardAsDeletedWhenCardIsActive() {
        Card card = activeCard();

        Card deleted = card.deleted();

        assertThat(deleted.status()).isEqualTo(CardStatus.DELETED);
        assertThat(deleted.id()).isEqualTo(card.id());
    }

    @Test
    void shouldThrowIllegalStateWhenCardIsAlreadyDeleted() {
        Card card = cardWith(CardStatus.DELETED, BALANCE);

        assertThatThrownBy(card::deleted)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already deleted");
    }

    @Test
    void shouldUpdateFieldsWhenNewDataIsValid() {
        Card card = activeCard();

        Card updated = card.withData(
                CardStatus.BLOCKED, new BigDecimal("500"), new BigDecimal("1000"), new BigDecimal("777"));

        assertThat(updated.status()).isEqualTo(CardStatus.BLOCKED);
        assertThat(updated.dailyLimit()).isEqualByComparingTo("500");
        assertThat(updated.monthlyLimit()).isEqualByComparingTo("1000");
        assertThat(updated.availableBalance()).isEqualByComparingTo("777");
    }

    @ParameterizedTest(name = "dailyLimit={0}, monthlyLimit={1}")
    @CsvSource({
            "-1, 500",
            "500, -1",
            "-1, -1"
    })
    void shouldThrowIllegalArgumentWhenLimitIsNegative(int dailyLimit, int monthlyLimit) {
        Card card = activeCard();

        assertThatThrownBy(() -> card.withData(
                CardStatus.ACTIVE, BigDecimal.valueOf(dailyLimit), BigDecimal.valueOf(monthlyLimit), BALANCE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
    }

    @Test
    void shouldThrowIllegalArgumentWhenDailyLimitExceedsMonthlyLimit() {
        Card card = activeCard();

        assertThatThrownBy(() -> card.withData(
                CardStatus.ACTIVE, new BigDecimal("1000"), new BigDecimal("500"), BALANCE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Daily limit can not be larger than monthly limit");
    }

    private Card activeCard() {
        return cardWith(CardStatus.ACTIVE, BALANCE);
    }

    private Card cardWith(CardStatus status, BigDecimal availableBalance) {
        return new Card(
                UUID.randomUUID(), PAN, BIN, "IVAN IVANOV",
                YearMonth.of(2027, 12), status, "643",
                new BigDecimal("100000"), new BigDecimal("500000"), availableBalance,
                "ISS001", Instant.parse("2026-01-01T00:00:00Z"));
    }
}
