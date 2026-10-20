package com.notfound.logistics.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

final class ServiceDatabase {
  enum Table {
    ORDERS("orders", "orders.p_orders"),
    DELIVERIES("delivery", "delivery.p_deliveries"),
    ROUTES("delivery", "delivery.p_delivery_routes"),
    DEDUCTIONS("hub", "hub.p_stock_deductions"),
    RESTORATIONS("hub", "hub.p_stock_restorations");

    final String service;
    final String relation;

    Table(String service, String relation) {
      this.service = service;
      this.relation = relation;
    }
  }

  private final ComposeStack stack;
  private final int port;
  private final Map<String, String> passwords = new HashMap<>();

  ServiceDatabase(ComposeStack stack) throws Exception {
    this.stack = stack;
    this.port = stack.port("postgres", 5432);
  }

  Connection connect(Table table, String label) throws Exception {
    final var properties = new Properties();
    properties.setProperty("user", table.service + "_app");
    if (!passwords.containsKey(table.service)) {
      passwords.put(
          table.service,
          stack.command(
              "exec",
              "-T",
              "postgres",
              "printenv",
              table.service.toUpperCase(Locale.ROOT) + "_DB_PASSWORD"));
    }
    properties.setProperty("password", passwords.get(table.service));
    properties.setProperty("ApplicationName", label);
    properties.setProperty("connectTimeout", "5");
    properties.setProperty("socketTimeout", "10");
    return DriverManager.getConnection(
        "jdbc:postgresql://127.0.0.1:" + port + "/" + table.service + "_db", properties);
  }

  List<JsonNode> rows(Table table, String field, UUID value) throws Exception {
    if (!List.of("id", "order_id", "delivery_id", "idempotency_key").contains(field)) {
      throw new IllegalArgumentException("Unsupported lookup field");
    }
    try (var connection = connect(table, "orders-e2e-control")) {
      connection.setReadOnly(true);
      try (var statement =
          connection.prepareStatement(
              "SELECT row_to_json(t) FROM " + table.relation + " t WHERE " + field + "=?")) {
        statement.setObject(1, value);
        statement.setQueryTimeout(10);
        final var rows = new ArrayList<JsonNode>();
        try (var result = statement.executeQuery()) {
          while (result.next()) {
            rows.add(GatewayClient.JSON.readTree(result.getString(1)));
          }
        }
        return rows;
      }
    }
  }

  DelayedCommit delay(Table table) throws Exception {
    if (table != Table.DELIVERIES && table != Table.RESTORATIONS && table != Table.DEDUCTIONS) {
      throw new IllegalArgumentException(
          "Only delivery creation and stock movements may be delayed");
    }
    final var label = "orders-recovery-" + UUID.randomUUID();
    final var connection = connect(table, label);
    final var delay = new DelayedCommit(connection);
    try {
      connection.setAutoCommit(false);
      try (var statement = connection.createStatement()) {
        statement.setQueryTimeout(10);
        statement.execute(
            """
            SET LOCAL lock_timeout='5s';
            SET LOCAL statement_timeout='45s';
            SET LOCAL idle_in_transaction_session_timeout='45s'
            """);
        statement.execute("LOCK TABLE " + table.relation + " IN SHARE MODE");
      }
      assertThat(
              count(
                  table,
                  """
                  SELECT count(*) FROM pg_locks l JOIN pg_stat_activity a ON a.pid=l.pid
                  WHERE a.application_name=? AND l.granted AND l.mode='ShareLock'
                    AND l.relation='%s'::regclass
                  """
                      .formatted(table.relation),
                  label))
          .as("Fault injection must hold the actual table lock")
          .isPositive();
      delay.onClose =
          () ->
              await("Fault injection session closed")
                  .atMost(Duration.ofSeconds(10))
                  .until(
                      () ->
                          count(
                                  table,
                                  "SELECT count(*) FROM pg_stat_activity WHERE application_name=?",
                                  label)
                              == 0);
      return delay;
    } catch (Exception | AssertionError failure) {
      try {
        delay.close();
      } catch (Exception cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
      throw failure;
    }
  }

  /** 전용 스택에서 복구 실패 예산 소진·최종 저장 실패를 실제 HTTP 경로로 만든다. */
  AutoCloseable rejectRecoveryWrite(Table table) throws Exception {
    if (table != Table.RESTORATIONS && table != Table.ORDERS)
      throw new IllegalArgumentException("Only recovery test writes may be rejected");
    final var name = "recovery_failure_" + UUID.randomUUID().toString().replace("-", "");
    final var function = table.service + "." + name;
    final var event = table == Table.ORDERS ? "UPDATE" : "INSERT";
    final var condition = table == Table.ORDERS ? " WHEN (NEW.status = 'CANCELLED')" : "";
    try (var connection = connect(table, "orders-e2e-recovery-failure");
        var statement = connection.createStatement()) {
      statement.setQueryTimeout(10);
      connection.setAutoCommit(false);
      statement.execute("CREATE FUNCTION " + function + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test recovery write failure'; END $$");
      statement.execute("CREATE TRIGGER " + name + " BEFORE " + event + " ON " + table.relation
          + " FOR EACH ROW" + condition + " EXECUTE FUNCTION " + function + "()");
      connection.commit();
    }
    return () -> {
      try (var connection = connect(table, "orders-e2e-recovery-failure-cleanup");
          var statement = connection.createStatement()) {
        statement.setQueryTimeout(10);
        connection.setAutoCommit(false);
        statement.execute("DROP TRIGGER " + name + " ON " + table.relation);
        statement.execute("DROP FUNCTION " + function + "()");
        connection.commit();
      }
    };
  }

  void assertInsertWaiting(Table table) throws Exception {
    assertThat(
            count(
                table,
                """
                SELECT count(*) FROM pg_locks
                WHERE database=(SELECT oid FROM pg_database WHERE datname=current_database())
                  AND NOT granted AND mode='RowExclusiveLock' AND relation=?::regclass
                """,
                table.relation))
        .as("Real service INSERT must be waiting")
        .isPositive();
  }

  /** 전용 스택의 테스트가 입고한 1개만 회수한다. 예상 수량이 아니면 임의 보정하지 않는다. */
  AutoCloseable testReplenishment(UUID stockId, int before) {
    return () -> {
      try (var connection = connect(Table.DEDUCTIONS, "orders-e2e-replenishment-cleanup");
          var statement =
              connection.prepareStatement(
                  "UPDATE hub.p_stocks SET quantity=? WHERE id=? AND quantity=?")) {
        statement.setInt(1, before);
        statement.setObject(2, stockId);
        statement.setInt(3, before + 1);
        statement.setQueryTimeout(10);
        assertThat(statement.executeUpdate()).as("Remove exactly the test replenishment").isOne();
      }
    };
  }

  /** 배송 생성의 확정 거절을 만들고 이 테스트가 숨긴 기존 경로만 복원한다. */
  AutoCloseable hideHubRoute(UUID departure, UUID arrival) throws Exception {
    var marker = "orders-e2e-" + UUID.randomUUID();
    UUID id;
    try (var connection = connect(Table.DEDUCTIONS, "orders-e2e-route-control");
        var statement =
            connection.prepareStatement(
                "UPDATE hub.p_hub_routes SET deleted_at=now(), deleted_by=? WHERE"
                    + " departure_hub_id=? AND arrival_hub_id=? AND deleted_at IS NULL"
                    + " RETURNING id")) {
      statement.setString(1, marker);
      statement.setObject(2, departure);
      statement.setObject(3, arrival);
      statement.setQueryTimeout(10);
      try (var result = statement.executeQuery()) {
        assertThat(result.next()).as("Existing route required for failure injection").isTrue();
        id = result.getObject(1, UUID.class);
        assertThat(result.next()).isFalse();
      }
    }
    return () -> {
      try (var connection = connect(Table.DEDUCTIONS, "orders-e2e-route-cleanup");
          var statement =
              connection.prepareStatement(
                  "UPDATE hub.p_hub_routes SET deleted_at=NULL, deleted_by=NULL WHERE id=? AND"
                      + " deleted_by=?")) {
        statement.setObject(1, id);
        statement.setString(2, marker);
        statement.setQueryTimeout(10);
        assertThat(statement.executeUpdate()).as("Restore only this test's route").isOne();
      }
    };
  }

  private long count(Table table, String query, String value) throws Exception {
    try (var connection = connect(table, "orders-e2e-control");
        var statement = connection.prepareStatement(query)) {
      statement.setQueryTimeout(10);
      statement.setString(1, value);
      try (var result = statement.executeQuery()) {
        result.next();
        return result.getLong(1);
      }
    }
  }

  static final class DelayedCommit implements AutoCloseable {
    private final Connection connection;
    private Runnable onClose = () -> {};

    DelayedCommit(Connection connection) {
      this.connection = connection;
    }

    @Override
    public void close() throws SQLException {
      try {
        connection.rollback();
      } finally {
        try {
          connection.close();
        } finally {
          onClose.run();
        }
      }
    }
  }
}
