package com.notfound.logistics.e2e;

import static com.notfound.logistics.e2e.GatewayClient.expect;
import static com.notfound.logistics.e2e.ServiceDatabase.Table.DELIVERIES;
import static com.notfound.logistics.e2e.ServiceDatabase.Table.ORDERS;
import static com.notfound.logistics.e2e.ServiceDatabase.Table.RESTORATIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 4, unit = TimeUnit.MINUTES)
class OrdersRecoveryTest {
  @Test
  void recoversDeliveryCommittedAfterTimeoutAndOrdersShutdown() throws Exception {
    checkCase(false);
  }

  @Test
  void recoversRestorationCommittedAfterTimeoutAndOrdersShutdown() throws Exception {
    checkCase(true);
  }

  private void checkCase(boolean restoration) throws Exception {
    final var stack = ComposeStack.fromEnvironment();
    stack.requireDeliveryApis();
    final var database = new ServiceDatabase(stack);
    final var table = restoration ? RESTORATIONS : DELIVERIES;
    final var key = UUID.randomUUID();
    UUID orderId = null;
    try (var client = new GatewayClient(stack.gateway());
        var restart = new ComposeStack.OrdersRestart(stack)) {
      final var owner = client.login(stack, "devseed", "USER");
      final var master = client.login(stack, "devmaster", "MASTER");
      final int before = client.get(OrdersFlowTest.STOCK, master).path("quantity").asInt();
      assertThat(before).isGreaterThanOrEqualTo(3);
      final var body = OrdersFlowTest.sampleOrder();
      if (restoration) {
        final var created = client.request("POST", "/api/orders", owner, body, key);
        assertThat(created.status()).isIn(200, 201, 202);
        orderId = UUID.fromString(created.body().path("data").path("order_id").asText());
        OrdersFlowTest.confirmed(client, "/api/orders/" + orderId, owner);
      }
      try (var delay = database.delay(table)) {
        final var pending =
            restoration
                ? client.request("POST", "/api/orders/" + orderId + "/cancel", master, null, null)
                : client.request("POST", "/api/orders", owner, body, key);
        orderId = UUID.fromString(expect(pending, 202).path("order_id").asText());
        database.assertInsertWaiting(table);
        restart.stop();
      }
      final UUID existingId = orderId;
      final var committed =
          await("Downstream commit after caller timeout and Orders shutdown")
              .atMost(Duration.ofSeconds(30))
              .pollInterval(Duration.ofMillis(200))
              .until(() -> database.rows(table, "order_id", existingId), rows -> !rows.isEmpty());
      assertThat(committed).hasSize(1);
      if (restoration) {
        assertThat(committed.getFirst().path("created_by").asText()).isEqualTo("devmaster");
      }
      final var saved = database.rows(ORDERS, "id", orderId);
      assertThat(saved).hasSize(1);
      assertThat(saved.getFirst().path("status").asText())
          .isIn("PROCESSING", "CANCELLING", "RECOVERY_REQUIRED");
      assertThat(saved.getFirst().path("recovery_phase").asText())
          .isEqualTo(restoration ? "STOCK_RESTORATION" : "DELIVERY_CREATION");
      restart.start();
      final var path = "/api/orders/" + orderId;
      final var order = recovered(client, path, owner, restoration ? "CANCELLED" : "CONFIRMED");
      final var replay = expect(client.request("POST", "/api/orders", owner, body, key), 200);
      assertThat(replay.path("order_id").asText()).isEqualTo(orderId.toString());
      assertThat(replay.path("status").asText()).isEqualTo(order.path("status").asText());
      assertThat(database.rows(ORDERS, "idempotency_key", key)).hasSize(1);
      final var deliveries = database.rows(DELIVERIES, "order_id", orderId);
      assertThat(deliveries).hasSize(1);
      assertThat(deliveries.getFirst().path("delivery_id").asText())
          .isEqualTo(order.path("delivery_id").asText());
      assertThat(client.get(OrdersFlowTest.STOCK, master).path("quantity").asInt())
          .isEqualTo(restoration ? before : before - 3);
      final var audited = database.rows(ORDERS, "id", orderId).getFirst();
      assertThat(audited.path("created_by").asText()).isEqualTo("devseed");
      assertThat(audited.path("updated_by").asText())
          .isEqualTo(restoration ? "devmaster" : "devseed");
      OrdersFlowTest.cancel(client, path, master);
      final var receipts = database.rows(RESTORATIONS, "order_id", orderId);
      assertThat(receipts).hasSize(1);
      assertThat(receipts.getFirst().path("created_by").asText()).isEqualTo("devmaster");
      expect(client.request("DELETE", path, master, null, null), 204);
      assertThat(client.get(OrdersFlowTest.STOCK, master).path("quantity").asInt())
          .isEqualTo(before);
    } catch (Exception | AssertionError failure) {
      OrdersFlowTest.reportFailure(database, table.service + "-recovery", key, orderId);
      throw failure;
    }
  }

  static JsonNode recovered(GatewayClient client, String path, String owner, String status) {
    return await("Automatic recovery of the same order after restart")
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(200))
        .until(
            () -> {
              final var response = client.request("GET", path, owner, null, null);
              if (List.of(502, 503, 504).contains(response.status())) {
                return GatewayClient.JSON.nullNode();
              }
              final var order = expect(response, 200);
              assertThat(order.path("status").asText())
                  .isIn("PROCESSING", "CANCELLING", "RECOVERY_REQUIRED", status);
              return order;
            },
            order -> order.path("status").asText().equals(status));
  }
}
