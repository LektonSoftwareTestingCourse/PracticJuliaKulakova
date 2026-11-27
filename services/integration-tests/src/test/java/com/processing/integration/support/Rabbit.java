package com.processing.integration.support;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.AlreadyClosedException;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static org.awaitility.Awaitility.await;

/**
 * Операции с RabbitMQ: топология DLQ, публикация и чтение сообщений.
 */
public final class Rabbit {

    public static final String EXCHANGE = "smp.transactions";

    public static final String ROUTING_KEY = "transaction.log";

    public static final String QUEUE = "transaction-log";

    public static final String DLX_EXCHANGE = "smp.transactions.dlx";

    public static final String DLQ = "transaction-log-dlq";

    public static final String DLQ_ROUTING_KEY = "transaction-log";

    private static final int DLQ_TTL_MS = 60_000;

    private Rabbit() {
    }

    public static void declareDeadLetterTopology(
            String host, int port, String username, String password) {
        withChannel(host, port, username, password, channel -> {
            channel.exchangeDeclare(DLX_EXCHANGE, "direct", true);
            channel.queueDeclare(DLQ, true, false, false,
                    Map.of("x-message-ttl", DLQ_TTL_MS));
            channel.queueBind(DLQ, DLX_EXCHANGE, DLQ_ROUTING_KEY);
        });
    }

    public static void awaitQueueDeclared(
            String host, int port, String username, String password, Duration timeout) {
        await()
                .atMost(timeout)
                .pollInterval(Duration.ofMillis(250))
                .until(() -> isQueueDeclared(host, port, username, password));
    }

    private static boolean isQueueDeclared(
            String host, int port, String username, String password) {
        try {
            withChannel(host, port, username, password, channel ->
                    channel.queueDeclarePassive(QUEUE));
            return true;
        } catch (RuntimeException notDeclaredYet) {
            return false;
        }
    }

    public static void publishRaw(
            String host, int port, String username, String password, byte[] body) {
        withChannel(host, port, username, password, channel -> channel.basicPublish(
                EXCHANGE, ROUTING_KEY,
                new AMQP.BasicProperties.Builder()
                        .contentType("application/json")
                        .deliveryMode(2)
                        .build(),
                body));
    }

    public static void publishJavaSerialized(
            String host, int port, String username, String password, Object payload) {
        byte[] body = serialize(payload);
        withChannel(host, port, username, password, channel -> channel.basicPublish(
                EXCHANGE, ROUTING_KEY,
                new AMQP.BasicProperties.Builder()
                        .contentType("application/x-java-serialized-object")
                        .deliveryMode(2)
                        .build(),
                body));
    }

    public static int depth(String host, int port, String username, String password, String queue) {
        int[] depth = new int[1];
        withChannel(host, port, username, password, channel ->
                depth[0] = channel.queueDeclarePassive(queue).getMessageCount());
        return depth[0];
    }

    public static byte[] peek(String host, int port, String username, String password, String queue) {
        byte[][] body = new byte[1][];
        withChannel(host, port, username, password, channel -> {
            GetResponse response = channel.basicGet(queue, false);
            if (response != null) {
                body[0] = response.getBody();
                channel.basicNack(response.getEnvelope().getDeliveryTag(), false, true);
            }
        });
        return body[0];
    }

    public static void purge(String host, int port, String username, String password, String queue) {
        withChannel(host, port, username, password, channel -> {
            try {
                channel.queueDeclarePassive(queue);
            } catch (IOException notDeclared) {
                return;
            }
            channel.queuePurge(queue);
        });
    }

    public static void purgeAll(String host, int port, String username, String password) {
        purge(host, port, username, password, QUEUE);
        purge(host, port, username, password, DLQ);
    }

    public static String asText(byte[] body) {
        return body == null ? null : new String(body, StandardCharsets.UTF_8);
    }

    private static byte[] serialize(Object payload) {
        try (var buffer = new ByteArrayOutputStream();
             var output = new ObjectOutputStream(buffer)) {
            output.writeObject(payload);
            output.flush();
            return buffer.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось сериализовать сообщение", e);
        }
    }

    private static void withChannel(
            String host, int port, String username, String password, ChannelAction action) {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(host);
        factory.setPort(port);
        factory.setUsername(username);
        factory.setPassword(password);

        Connection connection = null;
        Channel channel = null;
        try {
            connection = factory.newConnection();
            channel = connection.createChannel();
            action.run(channel);
        } catch (IOException | TimeoutException e) {
            throw new IllegalStateException("Ошибка работы с RabbitMQ", e);
        } finally {
            if (channel != null && channel.isOpen()) {
                try {
                    channel.close();
                } catch (IOException | AlreadyClosedException e) {
                }
            }
            if (connection != null && connection.isOpen()) {
                try {
                    connection.close();
                } catch (IOException e) {
                }
            }
        }
    }

    @FunctionalInterface
    private interface ChannelAction {
        void run(Channel channel) throws IOException;
    }
}