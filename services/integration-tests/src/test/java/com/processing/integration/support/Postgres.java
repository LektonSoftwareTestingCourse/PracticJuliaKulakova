package com.processing.integration.support;

import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Прямые запросы в PostgreSQL-контейнер: по отдельной базе на каждый сервис.
 */
public final class Postgres {

    public static final String CARD_MANAGEMENT_DB = "card_management";

    public static final String AUTHORIZATION_DB = "authorization";

    public static final String TRANSACTION_LOGGER_DB = "transaction_logger";

    private Postgres() {
    }

    public static void createServiceDatabases(PostgreSQLContainer<?> container) {
        for (String database : List.of(CARD_MANAGEMENT_DB, AUTHORIZATION_DB, TRANSACTION_LOGGER_DB)) {
            executeOnAdmin(container, "CREATE DATABASE \"" + database + "\"");
        }
    }

    public static void executeOnAdmin(PostgreSQLContainer<?> container, String sql) {
        try (Connection connection = DriverManager.getConnection(
                container.getJdbcUrl(), container.getUsername(), container.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("already exists")) {
                return;
            }
            throw new IllegalStateException("Не удалось выполнить SQL: " + sql, e);
        }
    }

    public static List<Object> querySingleRow(
            PostgreSQLContainer<?> container, String database, String sql, Object... params) {
        try (Connection connection = connect(container, database);
             var prepared = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                prepared.setObject(i + 1, params[i]);
            }
            try (ResultSet resultSet = prepared.executeQuery()) {
                if (!resultSet.next()) {
                    return List.of();
                }
                int columns = resultSet.getMetaData().getColumnCount();
                List<Object> row = new ArrayList<>(columns);
                for (int column = 1; column <= columns; column++) {
                    row.add(resultSet.getObject(column));
                }
                return row;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Запрос не выполнен: " + sql, e);
        }
    }

    public static long queryCount(
            PostgreSQLContainer<?> container, String database, String sql, Object... params) {
        List<Object> row = querySingleRow(container, database, sql, params);
        if (row.isEmpty() || row.getFirst() == null) {
            return 0L;
        }
        return ((Number) row.getFirst()).longValue();
    }

    public static void execute(PostgreSQLContainer<?> container, String database, String sql) {
        try (Connection connection = connect(container, database);
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("SQL не выполнен: " + sql, e);
        }
    }

    public static Map<String, String> datasourceEnv(PostgreSQLContainer<?> container, String database) {
        return Map.of(
            "POSTGRES_HOST", container.getHost(),
            "POSTGRES_PORT", String.valueOf(container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)),
            "POSTGRES_DB", database,
            "POSTGRES_USER", container.getUsername(),
            "POSTGRES_PASSWORD", container.getPassword()
        );
    }

    private static Connection connect(PostgreSQLContainer<?> container, String database) throws SQLException {
        String url = "jdbc:postgresql://" + container.getHost() + ":"
            + container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + database;
        return DriverManager.getConnection(url, container.getUsername(), container.getPassword());
    }
}
