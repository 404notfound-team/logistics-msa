package com.notfound.logistics.e2e;

import static com.notfound.logistics.e2e.GatewayClient.expect;
import static com.notfound.logistics.e2e.ServiceDatabase.Table.DELIVERIES;
import static com.notfound.logistics.e2e.ServiceDatabase.Table.ORDERS;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Must run immediately after stack startup, before smoke or business API probes. */
class ColdStartTest {
  @Test
  @Timeout(value = 2, unit = TimeUnit.MINUTES)
  void firstLoginAndOrderCompleteWithoutRecoveryThenWarmRequestsRemainIdempotent()
      throws Exception {
    final var stack = ComposeStack.fromEnvironment();
    final var database = new ServiceDatabase(stack);
    final var created = new ArrayList<UUID>();
    UUID key = null;
    UUID orderId = null;
    try (var client = new GatewayClient(stack.gateway())) {
      final long loginStarted = System.nanoTime();
      final var owner = client.login(stack, "devseed", "USER");
      System.out.printf("First login: %.3f s%n", secondsSince(loginStarted));
      for (int attempt = 0; attempt < 2; attempt++) {
        key = UUID.randomUUID();
        orderId = null;
        final long started = System.nanoTime();
        final var response =
            client.request("POST", "/api/orders", owner, OrdersFlowTest.sampleOrder(), key);
        System.out.printf(
            "%s order: %.3f s, HTTP %d%n",
            attempt == 0 ? "First" : "Warm", secondsSince(started), response.status());
        final var data = response.body().path("data");
        if (data.hasNonNull("order_id")) {
          orderId = UUID.fromString(data.path("order_id").asText());
        }
        expect(response, 201);
        assertThat(data.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(data.hasNonNull("delivery_id")).isTrue();
        created.add(orderId);
        assertThat(database.rows(ORDERS, "idempotency_key", key)).hasSize(1);
        assertThat(database.rows(DELIVERIES, "order_id", orderId)).hasSize(1);
        final var replay =
            expect(
                client.request("POST", "/api/orders", owner, OrdersFlowTest.sampleOrder(), key),
                200);
        assertThat(replay.path("order_id").asText()).isEqualTo(orderId.toString());
        assertThat(replay.path("delivery_id").asText())
            .isEqualTo(data.path("delivery_id").asText());
      }
      final var master = client.login(stack, "devmaster", "MASTER");
      for (final var id : created) {
        final var path = "/api/orders/" + id;
        OrdersFlowTest.cancel(client, path, master);
        expect(client.request("DELETE", path, master, null, null), 204);
      }
    } catch (Exception | AssertionError failure) {
      if (key != null) {
        OrdersFlowTest.reportFailure(database, "cold-start", key, orderId);
      }
      throw failure;
    }
  }

  private static double secondsSince(long started) {
    return (System.nanoTime() - started) / 1_000_000_000.0;
  }
}
