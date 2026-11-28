package com.processing.integration.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;

/**
 * Свободные локальные порты для запуска сервисов СМП.
 */
public final class Ports {

    private Ports() {
    }

    public static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось выделить свободный порт", e);
        }
    }
}
