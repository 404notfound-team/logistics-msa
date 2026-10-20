package com.notfound.logistics.e2e;

import static com.notfound.logistics.e2e.GatewayClient.expect;
import static com.notfound.logistics.e2e.ServiceDatabase.Table.DELIVERIES;
import static com.notfound.logistics.e2e.ServiceDatabase.Table.ORDERS;
import static com.notfound.logistics.e2e.ServiceDatabase.Table.RESTORATIONS;
import static com.notfound.logistics.e2e.ServiceDatabase.Table.ROUTES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class OrdersFlowTest {
  static final String PRODUCT = "c0ffee39-0001-4000-8000-000000000001";
  static final String STOCK =
      "/api/hubs/00000000-0000-0000-0000-000000000001/products/" + PRODUCT + "/stocks";

  static Map<String, Object> sampleOrder() {
    return Map.of(
        "receiver_company_id",
        "c0ffee39-0000-4000-8000-000000000003",
        "supplier_company_id",
        "c0ffee39-0000-4000-8000-000000000001",
        "product_id",
        PRODUCT,
        "quantity",
        3,
        "delivery_address",
        "[연동검증] 가상 배송지",
        "recipient_name",
        "연동검증 수령인",
        "request_notes",
        "실제 서비스 연동 검증");
  }

  @Test
  @Timeout(value = 3, unit = TimeUnit.MINUTES)
  void createsOneOrderForEightRequestsThenCancelsRestoresAndDeletes() throws Exception {
    final var stack = ComposeStack.fromEnvironment();
    stack.requireDeliveryApis();
    final var database = new ServiceDatabase(stack);
    final var key = UUID.randomUUID();
    UUID orderId = null;
    try (var client = new GatewayClient(stack.gateway())) {
      final var master = client.login(stack, "devmaster", "MASTER");
      final var owner = client.login(stack, "devseed", "USER");
      final var other = client.login(stack, "devseed2", "USER");
      final int before = client.get(STOCK, master).path("quantity").asInt();
      assertThat(before).isGreaterThanOrEqualTo(3);
      final var body = sampleOrder();
      expect(client.request("POST", "/api/orders", null, body, key), 401);
      try (var executor = Executors.newFixedThreadPool(8)) {
        final var calls =
            java.util.stream.IntStream.range(0, 8)
                .mapToObj(
                    ignored ->
                        (Callable<GatewayClient.Response>)
                            () -> client.request("POST", "/api/orders", owner, body, key))
                .toList();
        final var responses = new java.util.ArrayList<GatewayClient.Response>();
        for (final var future : executor.invokeAll(calls, 40, TimeUnit.SECONDS)) {
          responses.add(future.get(1, TimeUnit.SECONDS));
        }
        for (final var response : responses) {
          assertThat(response.status())
              .as("Concurrent creation status, code=%s", response.body().path("code").asText())
              .isIn(200, 201, 202);
        }
        final var ids =
            responses.stream()
                .map(response -> response.body().path("data").path("order_id").asText())
                .distinct()
                .toList();
        assertThat(ids).hasSize(1);
        orderId = UUID.fromString(ids.getFirst());
        assertThat(responses.stream().filter(response -> response.status() == 201).count())
            .isLessThanOrEqualTo(1);
      }
      final var path = "/api/orders/" + orderId;
      final var order = confirmed(client, path, owner);
      assertThat(order.path("request_notes").asText()).isEqualTo(body.get("request_notes"));
      final var deliveryId = UUID.fromString(order.path("delivery_id").asText());
      final var deliveryPath = "/api/deliveries/" + deliveryId;
      final var shipping = client.get(deliveryPath, master);
      assertThat(shipping.path("order_id").asText()).isEqualTo(orderId.toString());
      assertThat(shipping.path("status").asText()).isEqualTo("HUB_WAITING");
      assertThat(client.get(STOCK, master).path("quantity").asInt()).isEqualTo(before - 3);
      expect(client.request("GET", path, other, null, null), 403);
      expect(client.request("POST", path + "/cancel", other, null, null), 403);
      expect(client.request("DELETE", path, owner, null, null), 403);
      assertThat(
              expect(client.request("POST", "/api/orders", owner, body, key), 200)
                  .path("order_id")
                  .asText())
          .isEqualTo(orderId.toString());
      cancel(client, path, master);
      expect(client.request("POST", path + "/cancel", owner, null, null), 200);
      assertThat(client.get(STOCK, master).path("quantity").asInt()).isEqualTo(before);
      assertThat(client.get(deliveryPath, master).path("status").asText()).isEqualTo("CANCELLED");
      final var saved = database.rows(ORDERS, "id", orderId);
      assertThat(saved).hasSize(1);
      assertThat(saved.getFirst().path("created_by").asText()).isEqualTo("devseed");
      assertThat(saved.getFirst().path("updated_by").asText()).isEqualTo("devmaster");
      final var receipts = database.rows(RESTORATIONS, "order_id", orderId);
      assertThat(receipts).hasSize(1);
      assertThat(receipts.getFirst().path("created_by").asText()).isEqualTo("devmaster");
      expect(client.request("DELETE", path, master, null, null), 204);
      expect(client.request("DELETE", path, master, null, null), 204);
      expect(client.request("GET", path, master, null, null), 404);
      expect(client.request("GET", deliveryPath, master, null, null), 404);
      assertThat(database.rows(ROUTES, "delivery_id", deliveryId))
          .isNotEmpty()
          .allSatisfy(route -> assertThat(route.hasNonNull("deleted_at")).isTrue());
      expect(client.request("POST", "/api/orders", owner, body, key), 409);
      assertThat(client.get(STOCK, master).path("quantity").asInt()).isEqualTo(before);
      assertThat(database.rows(ORDERS, "idempotency_key", key)).hasSize(1);
      assertThat(database.rows(DELIVERIES, "order_id", orderId)).hasSize(1);
    } catch (Exception | AssertionError failure) {
      reportFailure(database, "flow", key, orderId);
      throw failure;
    }
  }

  @Test
  @Timeout(value = 3, unit = TimeUnit.MINUTES)
  void assignsAnActiveStaffAccountAndAllowsItsOwnLoginToCompleteDelivery() throws Exception {
    var stack = ComposeStack.fromEnvironment();
    stack.requireDeliveryApis();
    var database = new ServiceDatabase(stack);
    var key = UUID.randomUUID();
    UUID orderId = null;
    try (var client = new GatewayClient(stack.gateway())) {
      var master = client.login(stack, "devmaster", "MASTER");
      var owner = client.login(stack, "devseed", "USER");
      var driver = client.login(stack, "devseed2", "USER");
      int before = client.get(STOCK, master).path("quantity").asInt();
      var created = expect(client.request("POST", "/api/orders", owner, sampleOrder(), key), 201);
      orderId = UUID.fromString(created.path("order_id").asText());
      String path = "/api/deliveries/" + created.path("delivery_id").asText();
      expect(
          client.request(
              "PATCH",
              path,
              master,
              Map.of("assignee_id", UUID.randomUUID(), "status", "HUB_MOVING"),
              null),
          404);
      expect(
          client.request(
              "PATCH",
              path,
              master,
              Map.of("assignee_id", "c0ffee39-0000-4000-8000-000000000099", "status", "HUB_MOVING"),
              null),
          409);
      var unchanged = client.get(path, master);
      assertThat(unchanged.path("status").asText()).isEqualTo("HUB_WAITING");
      assertThat(unchanged.path("assignee_id").isNull()).isTrue();
      expect(
          client.request(
              "PATCH",
              path,
              master,
              Map.of("assignee_id", "c0ffee39-0000-4000-8000-000000000098"),
              null),
          200);
      expect(client.request("PATCH", path, owner, Map.of("status", "HUB_MOVING"), null), 403);
      for (String status :
          java.util.List.of(
              "HUB_MOVING", "DESTINATION_HUB_ARRIVED", "COMPANY_MOVING", "DELIVERED")) {
        assertThat(
                expect(client.request("PATCH", path, driver, Map.of("status", status), null), 200)
                    .path("status")
                    .asText())
            .isEqualTo(status);
      }
      expect(client.request("DELETE", "/api/orders/" + orderId, master, null, null), 204);
      // 완결한 배송은 재고를 복원하지 않으므로 이 테스트의 출고 3개를 입고해 시작 수량으로 돌린다.
      assertThat(client.get(STOCK, master).path("quantity").asInt()).isEqualTo(before - 3);
      expect(client.request("POST", STOCK + "/add", master, Map.of("quantity", 3), null), 200);
      assertThat(client.get(STOCK, master).path("quantity").asInt()).isEqualTo(before);
    } catch (Exception | AssertionError failure) {
      reportFailure(database, "assignment", key, orderId);
      throw failure;
    }
  }

  static JsonNode confirmed(GatewayClient client, String path, String owner) {
    return await("Order confirmation")
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .until(
            () -> {
              final var order = client.get(path, owner);
              assertThat(order.path("status").asText())
                  .isIn("PROCESSING", "RECOVERY_REQUIRED", "CONFIRMED");
              return order;
            },
            order -> order.path("status").asText().equals("CONFIRMED"));
  }

  static void cancel(GatewayClient client, String path, String master) throws Exception {
    final var initial = client.request("POST", path + "/cancel", master, null, null);
    assertThat(initial.status()).isIn(200, 202);
    if (initial.status() == 200) {
      assertThat(initial.body().path("data").path("status").asText()).isEqualTo("CANCELLED");
      return;
    }
    await("Order cancellation")
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .until(
            () -> {
              final var order = client.get(path, master);
              if (order.path("status").asText().equals("CANCELLED")) {
                return true;
              }
              final var response = client.request("POST", path + "/cancel", master, null, null);
              assertThat(response.status()).isIn(200, 202);
              return response.status() == 200
                  && response.body().path("data").path("status").asText().equals("CANCELLED");
            });
  }

  static void reportFailure(ServiceDatabase database, String scenario, UUID key, UUID orderId) {
    if (orderId == null) {
      try {
        final var rows = database.rows(ORDERS, "idempotency_key", key);
        if (rows.size() == 1 && rows.getFirst().path("created_by").asText().equals("devseed")) {
          orderId = UUID.fromString(rows.getFirst().path("id").asText());
        }
      } catch (Exception lookupFailure) {
        System.err.println("Order lookup failed; use the idempotency key below.");
      }
    }
    System.err.printf(
        "FAIL orders-%s: order_id=%s, idempotency_key=%s. Inspect this order before retrying; do"
            + " not create a new key or estimate stock corrections.%n",
        scenario, orderId, key);
  }
}
