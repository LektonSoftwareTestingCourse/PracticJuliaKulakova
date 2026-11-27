package com.processing.integration.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * HTTP-клиент для вызовов сервисов СМП.
 */
public final class Http {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .findAndRegisterModules();

    private Http() {
    }

    public static Response post(String url, Object body) {
        try {
            String payload = MAPPER.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        } catch (IOException e) {
            throw new IllegalStateException("POST " + url + " завершился ошибкой ввода-вывода", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("POST " + url + " прерван", e);
        }
    }

    public static Response get(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        } catch (IOException e) {
            throw new IllegalStateException("GET " + url + " завершился ошибкой ввода-вывода", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GET " + url + " прерван", e);
        }
    }

    /**
     * HTTP-статус и тело ответа.
     */
    public record Response(int status, String body) {

        public JsonNode json() {
            try {
                return MAPPER.readTree(body == null || body.isBlank() ? "{}" : body);
            } catch (IOException e) {
                throw new IllegalStateException("Ответ не является JSON: " + body, e);
            }
        }

        public String stringField(String field) {
            JsonNode node = json().get(field);
            return node == null || node.isNull() ? null : node.asText();
        }

        public BigDecimal decimalField(String field) {
            JsonNode node = json().get(field);
            return node == null || node.isNull() ? null : node.decimalValue();
        }
    }
}
