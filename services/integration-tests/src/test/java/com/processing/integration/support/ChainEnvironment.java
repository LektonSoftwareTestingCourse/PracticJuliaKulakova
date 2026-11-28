package com.processing.integration.support;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;

import java.util.HashMap;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

/**
 * Окружение сквозного теста: PostgreSQL и RabbitMQ в контейнерах, заглушки
 * WireMock для BIN Lookup и Merchant Acquirer, четыре сервиса СМП процессами.
 */
public final class ChainEnvironment implements AutoCloseable {

    private static final String BIN_LOOKUP_PATH = "/api/bin/";
    private static final String ACQUIRER_FEE_PATH = "/api/simulator/merchant/fee";
    private static final String HEALTH_PATH = "/health";

    private final int cardManagementPort = Ports.freePort();
    private final int authorizationPort = Ports.freePort();
    private final int transactionLoggerPort = Ports.freePort();
    private final int switchPort = Ports.freePort();

    private final PostgreSQLContainer<?> postgres;
    private final RabbitMQContainer rabbit;
    private final WireMockServer binLookupStub;
    private final WireMockServer merchantAcquirerStub;

    private ServiceProcess cardManagement;
    private ServiceProcess authorization;
    private ServiceProcess transactionLogger;
    private ServiceProcess switchService;

    private ChainEnvironment(
            PostgreSQLContainer<?> postgres,
            RabbitMQContainer rabbit,
            WireMockServer binLookupStub,
            WireMockServer merchantAcquirerStub) {
        this.postgres = postgres;
        this.rabbit = rabbit;
        this.binLookupStub = binLookupStub;
        this.merchantAcquirerStub = merchantAcquirerStub;
    }

    public static ChainEnvironment start() {
        PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("postgres")
            .withUsername("smp")
            .withPassword("smp");
        RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management")
            .withAdminUser("smp")
            .withAdminPassword("smp");

        postgres.start();
        rabbit.start();
        Postgres.createServiceDatabases(postgres);

        WireMockServer binLookup = new WireMockServer(Ports.freePort());
        binLookup.start();
        stubBinLookup(binLookup);

        WireMockServer merchantAcquirer = new WireMockServer(Ports.freePort());
        merchantAcquirer.start();
        stubMerchantAcquirer(merchantAcquirer);

        ChainEnvironment environment = new ChainEnvironment(
            postgres, rabbit, binLookup, merchantAcquirer);
        environment.startServices();
        return environment;
    }

    private static void stubBinLookup(WireMockServer stub) {
        for (int index = 0; index < 5; index++) {
            String bin = "40000" + index;
            String issuerId = "ISS00" + (index + 1);
            stub.stubFor(get(urlEqualTo(BIN_LOOKUP_PATH + bin))
                .willReturn(aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"bin\":\"" + bin + "\",\"issuerId\":\"" + issuerId
                        + "\",\"issuerName\":\"Test Bank\"}")));
        }
    }

    private static void stubMerchantAcquirer(WireMockServer stub) {
        stub.stubFor(post(urlEqualTo(ACQUIRER_FEE_PATH))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"acquirerFee\":150}")));
    }

    private void startServices() {
        Map<String, String> rabbitEnv = rabbitEnvironment();

        Map<String, String> cardManagementEnv = new HashMap<>(Postgres.datasourceEnv(
            postgres, Postgres.CARD_MANAGEMENT_DB));
        cardManagementEnv.putAll(rabbitEnv);
        cardManagementEnv.put("METRICS_SCHEDULER_ENABLED", "false");
        cardManagement = ServiceProcess.start(
            "card-management", cardManagementPort, cardManagementEnv, HEALTH_PATH);

        Map<String, String> authorizationEnv = new HashMap<>(Postgres.datasourceEnv(
            postgres, Postgres.AUTHORIZATION_DB));
        authorizationEnv.put("CARD_MGMT_URL", baseUrl(cardManagementPort));
        authorizationEnv.put("BIN_LOOKUP_URL", binLookupStub.baseUrl());
        authorization = ServiceProcess.start(
            "authorization", authorizationPort, authorizationEnv, HEALTH_PATH);

        Map<String, String> loggerEnv = new HashMap<>(Postgres.datasourceEnv(
            postgres, Postgres.TRANSACTION_LOGGER_DB));
        loggerEnv.putAll(rabbitEnv);
        loggerEnv.put("SPRING_AMQP_DESERIALIZATION_TRUST_ALL", "true");
        transactionLogger = ServiceProcess.start(
            "transaction-logger", transactionLoggerPort, loggerEnv, HEALTH_PATH);

        Map<String, String> switchEnv = new HashMap<>(rabbitEnv);
        switchEnv.put("AUTH_URL", baseUrl(authorizationPort));
        switchEnv.put("LOGGER_URL", baseUrl(transactionLoggerPort));
        switchEnv.put("MERCHANT_URL", merchantAcquirerStub.baseUrl());
        switchService = ServiceProcess.start(
            "switch", switchPort, switchEnv, HEALTH_PATH);
    }

    private Map<String, String> rabbitEnvironment() {
        Map<String, String> environment = new HashMap<>();
        environment.put("SPRING_RABBITMQ_HOST", rabbit.getHost());
        environment.put("SPRING_RABBITMQ_PORT", String.valueOf(rabbit.getAmqpPort()));
        environment.put("SPRING_RABBITMQ_USERNAME", rabbit.getAdminUsername());
        environment.put("SPRING_RABBITMQ_PASSWORD", rabbit.getAdminPassword());
        return environment;
    }

    public static String baseUrl(int port) {
        return "http://localhost:" + port;
    }

    public PostgreSQLContainer<?> postgres() {
        return postgres;
    }

    public RabbitMQContainer rabbit() {
        return rabbit;
    }

    public String rabbitHost() {
        return rabbit.getHost();
    }

    public int rabbitPort() {
        return rabbit.getAmqpPort();
    }

    public String rabbitUser() {
        return rabbit.getAdminUsername();
    }

    public String rabbitPassword() {
        return rabbit.getAdminPassword();
    }

    public String switchUrl() {
        return baseUrl(switchPort);
    }

    public String cardManagementUrl() {
        return baseUrl(cardManagementPort);
    }

    public String transactionLoggerUrl() {
        return baseUrl(transactionLoggerPort);
    }

    @Override
    public void close() {
        closeQuietly(switchService);
        closeQuietly(transactionLogger);
        closeQuietly(authorization);
        closeQuietly(cardManagement);
        binLookupStub.stop();
        merchantAcquirerStub.stop();
        rabbit.stop();
        postgres.stop();
    }

    private static void closeQuietly(ServiceProcess process) {
        if (process != null) {
            process.close();
        }
    }
}
