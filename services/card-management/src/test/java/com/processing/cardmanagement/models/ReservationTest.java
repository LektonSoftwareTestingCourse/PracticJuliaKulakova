package com.processing.cardmanagement.models;

import com.processing.cardmanagement.exceptions.RollbackAlreadySatisfiedException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit-тесты доменной модели {@link Reservation}:
 * создание возврата средств и переход в состояние ROLLED_BACK.
 */
class ReservationTest {

    private static final String PAN = "4000001234567893";
    private static final String RRN = "629712345000";
    private static final BigDecimal AMOUNT = new BigDecimal("1000");

    @Test
    void shouldCreateRollbackWhenReservationIsReserved() {
        Reservation reservation = reservedReservation();

        ReservationRollback rollback = reservation.startRollback(AMOUNT);

        assertThat(rollback.reservationId()).isEqualTo(reservation.id());
        assertThat(rollback.pan()).isEqualTo(PAN);
        assertThat(rollback.rollbackAmount()).isEqualByComparingTo(AMOUNT);
        assertThat(rollback.rrn()).isEqualTo(RRN);
    }

    @Test
    void shouldThrowRollbackAlreadySatisfiedWhenReservationIsRolledBack() {
        Reservation reservation = rolledBackReservation();

        assertThatThrownBy(() -> reservation.startRollback(AMOUNT))
                .isInstanceOf(RollbackAlreadySatisfiedException.class)
                .hasMessageContaining(RRN);
    }

    @Test
    void shouldMarkReservationAsRolledBackWhenRollbackReferencesSameReservation() {
        Reservation reservation = reservedReservation();
        ReservationRollback rollback = reservation.startRollback(AMOUNT);

        Reservation updated = reservation.rolledBack(rollback);

        assertThat(updated.status()).isEqualTo(ReservationStatus.ROLLED_BACK);
        assertThat(updated.id()).isEqualTo(reservation.id());
    }

    @Test
    void shouldThrowIllegalArgumentWhenRollbackReferencesDifferentReservation() {
        Reservation reservation = reservedReservation();
        ReservationRollback foreignRollback = new ReservationRollback(
                UUID.randomUUID(), UUID.randomUUID(), PAN, AMOUNT, RRN, Instant.now());

        assertThatThrownBy(() -> reservation.rolledBack(foreignRollback))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Reservation reservedReservation() {
        return new Reservation(PAN, AMOUNT, RRN);
    }

    private Reservation rolledBackReservation() {
        Reservation reservation = new Reservation(PAN, AMOUNT, RRN);
        return reservation.rolledBack(reservation.startRollback(AMOUNT));
    }
}
