package com.processing.cardmanagement.repositories;

import com.processing.cardmanagement.config.MapperConfig;
import com.processing.cardmanagement.exceptions.PanCollisionException;
import com.processing.cardmanagement.mappers.CardExpiryDateMapper;
import com.processing.cardmanagement.models.Card;
import com.processing.cardmanagement.models.CardStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Персистентность карт на реальном PostgreSQL: round-trip, частичный уникальный
 * индекс {@code uk_cards_pan} (V5.8) и применение миграций V5.1–V5.8.
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true"
})
@Import({
    CardRepositoryPersistenceAdapter.class,
    CardExpiryDateMapper.class,
    MapperConfig.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CardRepositoryIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final AtomicInteger SEQUENCE = new AtomicInteger(0);

    @Autowired
    private CardRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute(
            "TRUNCATE TABLE cards, outbox_event RESTART IDENTITY CASCADE");
    }

    @Test
    @DisplayName("Round-trip: карта переживает цикл Java → SQL → Java без искажений")
    void shouldPersistAndReadCardRoundTrip() {
        Card card = newCard(uniquePan());

        Card saved = repository.create(card);
        Optional<Card> found = repository.findByPan(card.pan());

        assertThat(saved.id()).isEqualTo(card.id());
        assertThat(found).isPresent();
        Card reloaded = found.get();
        assertThat(reloaded.pan()).isEqualTo(card.pan());
        assertThat(reloaded.bin()).isEqualTo(card.bin());
        assertThat(reloaded.cardholderName()).isEqualTo(card.cardholderName());
        assertThat(reloaded.expiryDate()).isEqualTo(card.expiryDate());
        assertThat(reloaded.status()).isEqualTo(CardStatus.ACTIVE);
        assertThat(reloaded.currencyCode()).isEqualTo(card.currencyCode());
        assertThat(reloaded.dailyLimit()).isEqualByComparingTo(card.dailyLimit());
        assertThat(reloaded.monthlyLimit()).isEqualByComparingTo(card.monthlyLimit());
        assertThat(reloaded.availableBalance()).isEqualByComparingTo(card.availableBalance());
        assertThat(reloaded.issuerId()).isEqualTo(card.issuerId());
        assertThat(reloaded.createdAt()).isNotNull();
    }

    @Test
    @DisplayName("Уникальность: вторая активная карта с тем же PAN отклоняется (PanCollisionException)")
    void shouldRejectSecondActiveCardWithSamePan() {
        String pan = uniquePan();
        repository.create(newCard(pan));

        assertThatThrownBy(() -> repository.create(newCard(pan)))
            .isInstanceOf(PanCollisionException.class);

        assertThat(countCardsByPan(pan)).isEqualTo(1);
    }

    @Test
    @DisplayName("Частичный индекс: PAN удалённой карты можно переиспользовать")
    void shouldAllowReusingPanOfDeletedCard() {
        String pan = uniquePan();
        Card original = repository.create(newCard(pan));
        jdbcTemplate.update("UPDATE cards SET status = 'DELETED' WHERE id = ?", original.id());

        Card recreated = repository.create(newCard(pan));

        assertThat(recreated.id()).isNotEqualTo(original.id());
        assertThat(countCardsByPan(pan)).isEqualTo(2);
        assertThat(countCardsByStatus(pan, "DELETED")).isEqualTo(1);
        assertThat(countCardsByStatus(pan, "ACTIVE")).isEqualTo(1);
        assertThat(repository.findByPan(pan))
            .isPresent()
            .get()
            .extracting(Card::id)
            .isEqualTo(recreated.id());
    }

    @Test
    @DisplayName("Миграции: все версии V5.1–V5.8 применены на чистой БД")
    void shouldApplyAllFlywayMigrations() {
        List<String> versions = jdbcTemplate.queryForList(
            "SELECT version FROM flyway_schema_history WHERE success = true AND version IS NOT NULL",
            String.class
        );

        assertThat(versions).contains(
            "5.1", "5.2", "5.3", "5.4", "5.5", "5.6", "5.7", "5.8"
        );

        Integer partialIndexes = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM pg_indexes WHERE tablename = 'cards' "
                + "AND indexname = 'uk_cards_pan' AND indexdef LIKE '%status%'",
            Integer.class
        );
        assertThat(partialIndexes).isEqualTo(1);
    }

    private int countCardsByPan(String pan) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM cards WHERE pan = ?", Integer.class, pan);
        return count == null ? 0 : count;
    }

    private int countCardsByStatus(String pan, String status) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM cards WHERE pan = ? AND status = ?", Integer.class, pan, status);
        return count == null ? 0 : count;
    }

    private static String uniquePan() {
        long tail = System.nanoTime() % 100_000_000_000L;
        String body = "400000" + String.format("%010d", tail + SEQUENCE.incrementAndGet());
        return body + luhnCheckDigit(body.substring(0, 15));
    }

    private static int luhnCheckDigit(String digits15) {
        int sum = 0;
        for (int i = 0; i < digits15.length(); i++) {
            int digit = digits15.charAt(digits15.length() - 1 - i) - '0';
            if (i % 2 == 0) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
        }
        return (10 - (sum % 10)) % 10;
    }

    private static Card newCard(String pan) {
        return new Card(
            UUID.randomUUID(),
            pan,
            pan.substring(0, 6),
            "IVAN IVANOV",
            YearMonth.now().plusYears(3),
            CardStatus.ACTIVE,
            "643",
            new BigDecimal("15000000"),
            new BigDecimal("300000000"),
            new BigDecimal("1000000"),
            "ISS001",
            Instant.now()
        );
    }
}
