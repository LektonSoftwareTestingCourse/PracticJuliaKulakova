package com.processing.integration.support;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;

/**
 * Сервис СМП, запущенный отдельным процессом из boot-jar.
 */
public final class ServiceProcess implements AutoCloseable {

    private final String name;
    private final Process process;

    private ServiceProcess(String name, Process process) {
        this.name = name;
        this.process = process;
    }

    public static ServiceProcess start(
            String serviceName, int port, Map<String, String> environment, String healthPath) {
        Path jar = resolveJar(serviceName);
        List<String> command = new ArrayList<>();
        command.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-XX:TieredStopAtLevel=1");
        command.add("-Xmx512m");
        command.add("-jar");
        command.add(jar.toString());
        command.add("--server.port=" + port);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(jar.getParent().toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.environment().putAll(environment);
        builder.environment().put("SERVER_PORT", String.valueOf(port));
        builder.environment().put("PORT", String.valueOf(port));
        try {
            Process process = builder.start();
            ServiceProcess serviceProcess = new ServiceProcess(serviceName, process);
            serviceProcess.awaitHealthy(port, healthPath, Duration.ofMinutes(3));
            return serviceProcess;
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось запустить сервис " + serviceName, e);
        }
    }

    private void awaitHealthy(int port, String healthPath, Duration timeout) {
        String url = "http://localhost:" + port + healthPath;
        try {
            await()
                .atMost(timeout)
                .pollInterval(250, TimeUnit.MILLISECONDS)
                .until(() -> isHealthy(url));
        } catch (RuntimeException e) {
            close();
            throw new IllegalStateException("Сервис " + name + " не стал здоровым за " + timeout, e);
        }
    }

    private boolean isHealthy(String url) {
        if (!process.isAlive()) {
            throw new IllegalStateException(
                "Сервис " + name + " завершился с кодом " + process.exitValue()
                    + " до готовности health-эндпоинта");
        }
        try {
            Http.get(url).status();
            return true;
        } catch (RuntimeException notReadyYet) {
            return false;
        }
    }

    private static Path resolveJar(String serviceName) {
        String baseDir = System.getProperty("services.baseDir");
        Path targetDir = Paths.get(baseDir == null ? "../" + serviceName : baseDir + "/" + serviceName)
            .toAbsolutePath().normalize().resolve("target");
        if (!Files.isDirectory(targetDir)) {
            throw new IllegalStateException(
                "Каталог сборки не найден: " + targetDir
                    + ". Соберите модуль сервиса перед интеграционными тестами.");
        }
        try (DirectoryStream<Path> jars = Files.newDirectoryStream(targetDir, "*.jar")) {
            for (Path jar : jars) {
                String fileName = jar.getFileName().toString();
                if (fileName.startsWith("original-")) {
                    continue;
                }
                if (!fileName.endsWith("-sources.jar") && !fileName.endsWith("-javadoc.jar")) {
                    return jar;
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось прочитать " + targetDir, e);
        }
        throw new IllegalStateException("Boot-jar сервиса " + serviceName + " не найден в " + targetDir);
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    @Override
    public void close() {
        if (process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(20, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(10, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }
}
