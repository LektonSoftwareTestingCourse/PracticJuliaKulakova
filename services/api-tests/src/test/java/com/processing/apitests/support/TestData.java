package com.processing.apitests.support;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Фабрика уникальных тестовых данных: каждый тест получает собственные
 * идентификаторы (STAN, PAN, merchantId, terminalId, RRN), что исключает
 * пересечение данных между тестами и flaky-падения.
 */
public final class TestData {

    /** BIN из таблицы маршрутизации switch (application.yml: switch.bin-routing). */
    public static final String KNOWN_BIN = "400000";

    /** BIN, отсутствующий в таблице маршрутизации switch. */
    public static final String UNKNOWN_BIN = "999999";

    private static final AtomicInteger STAN_SEQUENCE =
            new AtomicInteger(ThreadLocalRandom.current().nextInt(1, 900_000));
    private static final AtomicInteger MERCHANT_SEQUENCE =
            new AtomicInteger(ThreadLocalRandom.current().nextInt(1, 900_000_000));
    private static final AtomicInteger TERMINAL_SEQUENCE =
            new AtomicInteger(ThreadLocalRandom.current().nextInt(1, 9_000));
    private static final AtomicInteger HOLDER_SEQUENCE =
            new AtomicInteger(ThreadLocalRandom.current().nextInt(1, 1_000_000));

    private TestData() {
    }

    public static String nextStan() {
        return String.format("%06d", STAN_SEQUENCE.updateAndGet(v -> v >= 999_999 ? 1 : v + 1));
    }

    public static String randomPan(String bin) {
        return bin + String.format("%010d", ThreadLocalRandom.current().nextLong(0, 10_000_000_000L));
    }

    /** PAN с известным BIN, которого гарантированно нет в card-management. */
    public static String panWithoutCard() {
        return randomPan(KNOWN_BIN);
    }

    /** PAN с BIN, которого нет в таблице маршрутизации switch. */
    public static String panWithUnknownBin() {
        return randomPan(UNKNOWN_BIN);
    }

    /** 15 символов: MERCH + 10 цифр (контракт AuthorizationRequest.merchantId). */
    public static String nextMerchantId() {
        return String.format("MERCH%010d",
                MERCHANT_SEQUENCE.updateAndGet(v -> v >= 999_999_999 ? 1 : v + 1));
    }

    /** 8 символов: TERM + 4 цифры (контракт AuthorizationRequest.terminalId). */
    public static String nextTerminalId() {
        return String.format("TERM%04d", TERMINAL_SEQUENCE.updateAndGet(v -> v >= 9999 ? 1 : v + 1));
    }

    /** 12 цифр — формат RRN из AuthorizationResponse. */
    public static String nextRrn() {
        return String.format("%012d", ThreadLocalRandom.current().nextLong(0, 1_000_000_000_000L));
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    public static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }

    /**
     * Уникальное имя держателя карты: только заглавные буквы и пробелы —
     * паттерн CreateCardRequest {@code ^[A-Z\s\-\.']+$} не допускает цифр.
     */
    public static String uniqueHolderName() {
        return "TEST USER " + lettersFrom(HOLDER_SEQUENCE.incrementAndGet());
    }

    private static String lettersFrom(int value) {
        StringBuilder letters = new StringBuilder();
        int rest = value;
        while (rest > 0) {
            letters.append((char) ('A' + rest % 26));
            rest /= 26;
        }
        return letters.reverse().toString();
    }
}
