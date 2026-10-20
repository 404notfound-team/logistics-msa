package com.notfound.logistics.e2e;

import static com.notfound.logistics.e2e.GatewayClient.expect;
import static com.notfound.logistics.e2e.ServiceDatabase.Table.ORDERS;
import static com.notfound.logistics.e2e.ServiceDatabase.Table.RESTORATIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 8, unit = TimeUnit.MINUTES)
class OrdersBlockedRecoveryTest {
  @Test
  void blockedManualRestorationSurvivesRestartAfterRemoteCommitAndLocalSaveFailure()
      throws Exception {
    final var stack = ComposeStack.fromEnvironment();
    stack.requireDeliveryApis();
    final var database = new ServiceDatabase(stack);
    final var key = UUID.randomUUID();
    UUID orderId = null;
    try (var client = new GatewayClient(stack.gateway());
        var restart = new ComposeStack.OrdersRestart(stack)) {
      final var owner = client.login(stack, "devseed", "USER");
      final var master = client.login(stack, "devmaster", "MASTER");
      final int before = client.get(OrdersFlowTest.STOCK, master).path("quantity").asInt();
      final var created =
          client.request("POST", "/api/orders", owner, OrdersFlowTest.sampleOrder(), key);
      orderId = UUID.fromString(expect(created, 201).path("order_id").asText());
      final var path = "/api/orders/" + orderId;
      OrdersFlowTest.confirmed(client, path, owner);
      JsonNode blocked;
      try (var failure = database.rejectRecoveryWrite(RESTORATIONS)) {
        expect(client.request("POST", path + "/cancel", owner, null, null), 202);
        blocked =
            await("Default twenty failures exhaust the persisted restoration budget")
                .atMost(Duration.ofMinutes(3))
                .pollInterval(Duration.ofSeconds(1))
                .until(
                    () -> client.get(path + "/recovery", master),
                    value -> value.path("recovery_blocked").asBoolean());
      }
      assertThat(blocked.path("recovery_attempts").asInt()).isEqualTo(20);
      assertThat(blocked.path("recovery_phase").asText()).isEqualTo("STOCK_RESTORATION");
      assertThat(database.rows(RESTORATIONS, "order_id", orderId)).isEmpty();
      final var oldCommand = command(blocked);
      try (var failure = database.rejectRecoveryWrite(ORDERS)) {
        expect(client.request("POST", path + "/recovery", master, oldCommand, null), 500);
        final var committed = database.rows(RESTORATIONS, "order_id", orderId);
        assertThat(committed).hasSize(1);
        assertThat(committed.getFirst().path("created_by").asText()).isEqualTo("devseed");
        restart.stop();
      }
      final var persisted = database.rows(ORDERS, "id", orderId).getFirst();
      assertThat(persisted.path("status").asText()).isEqualTo("CANCELLING");
      assertThat(persisted.path("recovery_blocked").asBoolean()).isTrue();
      assertThat(persisted.path("recovery_attempts").asInt()).isEqualTo(20);
      restart.start();
      final var current =
          await("Blocked recovery API available after Orders restart")
              .atMost(Duration.ofSeconds(60))
              .pollInterval(Duration.ofMillis(200))
              .until(
                  () -> client.request("GET", path + "/recovery", master, null, null),
                  response -> response.status() == 200);
      final var snapshot = expect(current, 200);
      assertThat(snapshot.path("status").asText()).isEqualTo("CANCELLING");
      assertThat(snapshot.path("recovery_blocked").asBoolean()).isTrue();
      assertThat(snapshot.path("recovery_attempts").asInt()).isEqualTo(20);
      expect(client.request("POST", path + "/recovery", owner, command(snapshot), null), 403);
      expect(client.request("POST", path + "/recovery", master, oldCommand, null), 409);
      final var resumed = command(snapshot);
      assertThat(
              expect(client.request("POST", path + "/recovery", master, resumed, null), 200)
                  .path("status")
                  .asText())
          .isEqualTo("CANCELLED");
      expect(client.request("POST", path + "/recovery", master, resumed, null), 409);
      assertThat(database.rows(RESTORATIONS, "order_id", orderId)).hasSize(1);
      final var saved = database.rows(ORDERS, "id", orderId).getFirst();
      assertThat(saved.path("updated_by").asText()).isEqualTo("devseed");
      assertThat(client.get(OrdersFlowTest.STOCK, master).path("quantity").asInt())
          .isEqualTo(before);
      expect(client.request("DELETE", path, master, null, null), 204);
    } catch (Exception | AssertionError failure) {
      OrdersFlowTest.reportFailure(database, "blocked-manual-recovery", key, orderId);
      throw failure;
    }
  }

  private Map<String, Object> command(JsonNode snapshot) {
    return Map.of(
        "expected_updated_at",
        snapshot.path("updated_at").asText(),
        "expected_phase",
        snapshot.path("recovery_phase").asText(),
        "expected_attempts",
        snapshot.path("recovery_attempts").asInt(),
        "upstream_checked",
        true);
  }
}
