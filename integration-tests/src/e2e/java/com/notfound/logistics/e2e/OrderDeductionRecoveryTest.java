package com.notfound.logistics.e2e;

import static com.notfound.logistics.e2e.GatewayClient.expect;
import static com.notfound.logistics.e2e.ServiceDatabase.Table.DEDUCTIONS;
import static com.notfound.logistics.e2e.ServiceDatabase.Table.DELIVERIES;
import static com.notfound.logistics.e2e.ServiceDatabase.Table.ORDERS;
import static com.notfound.logistics.e2e.ServiceDatabase.Table.RESTORATIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 현재 CI의 실제 서비스·분리 DB에서 차감 결과 유실과 확정 배송 실패 보상을 검사한다. */
@Timeout(value = 4, unit = TimeUnit.MINUTES)
class OrderDeductionRecoveryTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void deductionCommittedAfterTimeoutReplaysItsOriginalOutcomeAfterRestart(boolean rejected)
      throws Exception {
    var stack = ComposeStack.fromEnvironment();
    stack.requireDeliveryApis();
    var database = new ServiceDatabase(stack);
    var key = UUID.randomUUID();
    UUID orderId = null;
    try (var client = new GatewayClient(stack.gateway());
        var restart = new ComposeStack.OrdersRestart(stack)) {
      var owner = client.login(stack, "devseed", "USER");
      var master = client.login(stack, "devmaster", "MASTER");
      var stock = client.get(OrdersFlowTest.STOCK, master);
      int before = stock.path("quantity").asInt();
      assertThat(before).isGreaterThanOrEqualTo(3);
      var body = new java.util.HashMap<String, Object>(OrdersFlowTest.sampleOrder());
      body.put("quantity", rejected ? before + 1 : 3);
      try (var delay = database.delay(DEDUCTIONS)) {
        var pending = expect(client.request("POST", "/api/orders", owner, body, key), 202);
        orderId = UUID.fromString(pending.path("order_id").asText());
        database.assertInsertWaiting(DEDUCTIONS);
        restart.stop();
      }
      UUID id = orderId;
      var receipt =
          await("Hub deduction committed after Orders stopped")
              .atMost(Duration.ofSeconds(30))
              .until(() -> database.rows(DEDUCTIONS, "order_id", id), rows -> !rows.isEmpty());
      assertThat(receipt).hasSize(1);
      assertThat(database.rows(ORDERS, "id", id).getFirst().path("recovery_phase").asText())
          .isEqualTo("STOCK_DEDUCTION");
      if (rejected) {
        assertThat(receipt.getFirst().path("failure_code").asText())
            .isEqualTo("INSUFFICIENT_STOCK");
        expect(
            client.request(
                "POST", OrdersFlowTest.STOCK + "/add", master, Map.of("quantity", 1), null),
            200);
        try (var replenishment =
            database.testReplenishment(UUID.fromString(stock.path("id").asText()), before)) {
          restart.start();
          OrdersRecoveryTest.recovered(client, "/api/orders/" + id, owner, "FAILED");
          assertThat(
                  expect(client.request("POST", "/api/orders", owner, body, key), 200)
                      .path("status")
                      .asText())
              .isEqualTo("FAILED");
          assertThat(client.get(OrdersFlowTest.STOCK, master).path("quantity").asInt())
              .isEqualTo(before + 1);
          assertThat(database.rows(DEDUCTIONS, "order_id", id)).hasSize(1);
          assertThat(database.rows(DELIVERIES, "order_id", id)).isEmpty();
          expect(client.request("DELETE", "/api/orders/" + id, master, null, null), 204);
        }
      } else {
        assertThat(receipt.getFirst().path("failure_code").isNull()).isTrue();
        restart.start();
        OrdersFlowTest.confirmed(client, "/api/orders/" + id, owner);
        expect(client.request("POST", "/api/orders", owner, body, key), 200);
        assertThat(client.get(OrdersFlowTest.STOCK, master).path("quantity").asInt())
            .isEqualTo(before - 3);
        assertThat(database.rows(DEDUCTIONS, "order_id", id)).hasSize(1);
        assertThat(database.rows(DELIVERIES, "order_id", id)).hasSize(1);
        OrdersFlowTest.cancel(client, "/api/orders/" + id, master);
        expect(client.request("DELETE", "/api/orders/" + id, master, null, null), 204);
      }
      assertThat(client.get(OrdersFlowTest.STOCK, master).path("quantity").asInt())
          .isEqualTo(before);
    } catch (Exception | AssertionError failure) {
      OrdersFlowTest.reportFailure(database, "deduction-recovery", key, orderId);
      throw failure;
    }
  }

  @Test
  void definiteDeliveryRejectionRestoresRealHubStockBeforeFailingOrder() throws Exception {
    var stack = ComposeStack.fromEnvironment();
    stack.requireDeliveryApis();
    var database = new ServiceDatabase(stack);
    var key = UUID.randomUUID();
    UUID orderId = null;
    try (var client = new GatewayClient(stack.gateway())) {
      var owner = client.login(stack, "devseed", "USER");
      var master = client.login(stack, "devmaster", "MASTER");
      int before = client.get(OrdersFlowTest.STOCK, master).path("quantity").asInt();
      var body = OrdersFlowTest.sampleOrder();
      var departure =
          UUID.fromString(
              client
                  .get("/api/companies/" + body.get("supplier_company_id"), master)
                  .path("hub_id")
                  .asText());
      var arrival =
          UUID.fromString(
              client
                  .get("/api/companies/" + body.get("receiver_company_id"), master)
                  .path("hub_id")
                  .asText());
      assertThat(departure).isNotEqualTo(arrival);
      try (var missingRoute = database.hideHubRoute(departure, arrival)) {
        var failure = expect(client.request("POST", "/api/orders", owner, body, key), 502);
        assertThat(failure.path("code").asText()).isEqualTo("DELIVERY_CREATION_FAILED");
      }
      var saved = database.rows(ORDERS, "idempotency_key", key);
      assertThat(saved).hasSize(1);
      orderId = UUID.fromString(saved.getFirst().path("id").asText());
      assertThat(saved.getFirst().path("status").asText()).isEqualTo("FAILED");
      assertThat(database.rows(DEDUCTIONS, "order_id", orderId)).hasSize(1);
      assertThat(database.rows(RESTORATIONS, "order_id", orderId)).hasSize(1);
      assertThat(database.rows(DELIVERIES, "order_id", orderId)).isEmpty();
      expect(client.request("POST", "/api/orders", owner, body, key), 200);
      assertThat(database.rows(RESTORATIONS, "order_id", orderId)).hasSize(1);
      assertThat(client.get(OrdersFlowTest.STOCK, master).path("quantity").asInt())
          .isEqualTo(before);
      expect(client.request("DELETE", "/api/orders/" + orderId, master, null, null), 204);
    } catch (Exception | AssertionError failure) {
      OrdersFlowTest.reportFailure(database, "delivery-compensation", key, orderId);
      throw failure;
    }
  }
}
