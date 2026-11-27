package com.processing.authorization.repositories;

import com.processing.authorization.entities.LimitUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Атомарный {@code upsertLimitUsage} на реальном PostgreSQL: накопление сумм
 * и отказ при превышении дневного и месячного лимитов.
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=update",
    "spring.flyway.enabled=true"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LimitUsageRepositoryIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final BigDecimal DAILY_LIMIT = new BigDecimal("1000");
    private static final BigDecimal MONTHLY_LIMIT = new BigDecimal("5000");

    @Autowired
    private LimitUsageRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate txTemplate;

    @BeforeEach
    void setUp() {
        txTemplate = new TransactionTemplate(transactionManager);
        jdbcTemplate.execute("TRUNCATE TABLE limit_usage");
    }

    @Test
    @DisplayName("Первый upsert создаёт запись и возвращает признак успешного обновления")
    void shouldInsertOnFirstUpsert() {
        String pan = "4000000000000002";
        LocalDate date = LocalDate.of(2026, 3, 10);
        BigDecimal amount = new BigDecimal("300");

        int updated = upsert(pan, date, amount, DAILY_LIMIT, MONTHLY_LIMIT);

        assertThat(updated).isEqualTo(1);
        Optional<LimitUsage> saved = repository.findAll().stream().findFirst();
        assertThat(saved).isPresent();
        assertThat(saved.get().getPan()).isEqualTo(pan);
        assertThat(saved.get().getUsageDate()).isEqualTo(date);
        assertThat(saved.get().getDailyAmount()).isEqualByComparingTo("300");
        assertThat(saved.get().getMonthlyAmount()).isEqualByComparingTo("300");
        assertThat(countRows(pan)).isEqualTo(1);
    }

    @Test
    @DisplayName("Повторный upsert не создаёт дубликат, а атомарно наращивает суммы")
    void shouldAccumulateOnSecondUpsert() {
        String pan = "4000000000000003";
        LocalDate date = LocalDate.of(2026, 3, 11);
        upsert(pan, date, new BigDecimal("300"), DAILY_LIMIT, MONTHLY_LIMIT);

        int updated = upsert(pan, date, new BigDecimal("200"), DAILY_LIMIT, MONTHLY_LIMIT);

        assertThat(updated).isEqualTo(1);
        LimitUsage row = repository.findAll().getFirst();
        assertThat(row.getDailyAmount()).isEqualByComparingTo("500");
        assertThat(row.getMonthlyAmount()).isEqualByComparingTo("500");
        assertThat(countRows(pan)).isEqualTo(1);
    }

    @Test
    @DisplayName("Граница дневного лимита: превышение отклоняется, состояние записи прежнее")
    void shouldRejectUpsertExceedingDailyLimit() {
        String pan = "4000000000000004";
        LocalDate date = LocalDate.of(2026, 3, 12);
        upsert(pan, date, new BigDecimal("700"), DAILY_LIMIT, MONTHLY_LIMIT);

        int updated = upsert(pan, date, new BigDecimal("400"), DAILY_LIMIT, MONTHLY_LIMIT);

        assertThat(updated).isZero();
        LimitUsage row = repository.findAll().getFirst();
        assertThat(row.getDailyAmount()).isEqualByComparingTo("700");
        assertThat(row.getMonthlyAmount()).isEqualByComparingTo("700");
        assertThat(countRows(pan)).isEqualTo(1);
    }

    @Test
    @DisplayName("Граница месячного лимита: превышение отклоняется по месячной сумме")
    void shouldRejectUpsertExceedingMonthlyLimit() {
        String pan = "4000000000000005";
        LocalDate date = LocalDate.of(2026, 3, 13);
        BigDecimal monthly = new BigDecimal("900");
        upsert(pan, date, new BigDecimal("600"), new BigDecimal("5000"), monthly);

        int updated = upsert(pan, date, new BigDecimal("400"), new BigDecimal("5000"), monthly);

        assertThat(updated).isZero();
        LimitUsage row = repository.findAll().getFirst();
        assertThat(row.getDailyAmount()).isEqualByComparingTo("600");
        assertThat(row.getMonthlyAmount()).isEqualByComparingTo("600");
    }

    @Test
    @DisplayName("Месячная сумма подхватывается из предыдущей записи месяца")
    void shouldCarryMonthlyAmountAcrossDays() {
        String pan = "4000000000000006";
        upsert(pan, LocalDate.of(2026, 3, 20), new BigDecimal("250"), DAILY_LIMIT, MONTHLY_LIMIT);

        int updated = upsert(pan, LocalDate.of(2026, 3, 21), new BigDecimal("150"), DAILY_LIMIT, MONTHLY_LIMIT);

        assertThat(updated).isEqualTo(1);
        BigDecimal secondDayMonthly = jdbcTemplate.queryForObject(
            "SELECT monthly_amount FROM limit_usage WHERE pan = ? AND usage_date = ?",
            BigDecimal.class, pan, LocalDate.of(2026, 3, 21));
        assertThat(secondDayMonthly).isEqualByComparingTo("400");
        assertThat(countRows(pan)).isEqualTo(2);
    }

    private int upsert(String pan, LocalDate date, BigDecimal amount,
                       BigDecimal dailyLimit, BigDecimal monthlyLimit) {
        Integer result = txTemplate.execute(status ->
            repository.upsertLimitUsage(pan, date, amount, dailyLimit, monthlyLimit));
        return result == null ? 0 : result;
    }

    private int countRows(String pan) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM limit_usage WHERE pan = ?", Integer.class, pan);
        return count == null ? 0 : count;
    }
}
