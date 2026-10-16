package com.notfound.logistics.hub.presentation.controller.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notfound.logistics.common.api.ApiResponse;
import com.notfound.logistics.hub.HubApplication;
import com.notfound.logistics.hub.support.CompanyApiTestServer;
import com.notfound.logistics.hub.support.HubSecurityTest;
import com.notfound.logistics.hub.support.HubSecurityTestTokens;
import feign.Feign;
import feign.FeignException;
import feign.Retryer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.http.HttpMessageConverters;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.openfeign.support.ResponseEntityDecoder;
import org.springframework.cloud.openfeign.support.SpringDecoder;
import org.springframework.cloud.openfeign.support.SpringEncoder;
import org.springframework.cloud.openfeign.support.SpringMvcContract;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@ActiveProfiles("local")
@SpringBootTest(
    classes = HubApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "eureka.client.enabled=false",
      "management.tracing.enabled=false",
      "springdoc.api-docs.enabled=false"
    })
@HubSecurityTest
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrdersStockContractIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> DATABASE =
      new PostgreSQLContainer<>("postgres:18")
          .withDatabaseName("hub_db")
          .withUsername("hub_test")
          .withPassword("hub-test-only")
          .withInitScript("db/hub-test-schema.sql");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("hub.clients.company-url", CompanyApiTestServer::url);
    registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
    registry.add("spring.datasource.username", DATABASE::getUsername);
    registry.add("spring.datasource.password", DATABASE::getPassword);
  }

  @LocalServerPort private int port;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper mapper;
  @Autowired private ObjectFactory<HttpMessageConverters> converters;
  private final UUID hub = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private UUID product;
  private UUID stock;

  @BeforeEach
  void createStockThroughAuthenticatedExternalApi() throws Exception {
    jdbc.update("DELETE FROM hub.p_stock_restorations");
    jdbc.update("DELETE FROM hub.p_stocks");
    product = UUID.randomUUID();
    var response =
        post(
            "/api/stocks",
            mapper.writeValueAsString(
                Map.of("hub_id", hub, "product_id", product, "quantity", Long.MAX_VALUE)),
            Map.of("Authorization", "Bearer " + HubSecurityTestTokens.token("alice", "MASTER")));
    assertThat(response.statusCode()).isEqualTo(201);
    stock = UUID.fromString(mapper.readTree(response.body()).at("/data/id").asText());
    assertThat(row().get("created_by")).isEqualTo("alice");
  }

  @ParameterizedTest
  @ValueSource(longs = {1, 3_000_000_000L, Long.MAX_VALUE})
  void ordersFeignDecodesRemainingQuantityIncludingLongBoundaries(long quantity) {
    var result =
        client(HubSecurityTestTokens.ORDERS, "alice")
            .deduct(new Deduction(UUID.randomUUID(), hub, product, quantity));
    long remaining = Long.MAX_VALUE - quantity;
    assertThat(result.data()).isEqualTo(new Stock(stock, hub, product, remaining));
    assertThat(row().get("quantity")).isEqualTo(remaining);
    assertThat(row().get("created_by")).isEqualTo("alice");
    assertThat(row().get("updated_by")).isEqualTo("alice");
  }

  @Test
  void restorationReplaysTheCommittedReceiptWithoutChangingQuantityOrAudit() throws Exception {
    var client = client(HubSecurityTestTokens.ORDERS, "alice");
    client.deduct(new Deduction(UUID.randomUUID(), hub, product, 10));
    var request = new Deduction(UUID.randomUUID(), hub, product, 3);
    var restored = client.restore(request);
    assertThat(restored.data()).isEqualTo(new Stock(stock, hub, product, Long.MAX_VALUE - 7));
    var receipt =
        jdbc.queryForMap(
            "SELECT * FROM hub.p_stock_restorations WHERE order_id=?", request.orderId());
    assertThat(receipt.get("created_by")).isEqualTo("alice");
    client.deduct(new Deduction(UUID.randomUUID(), hub, product, 1));
    var before = row();
    assertThat(client(HubSecurityTestTokens.ORDERS, "admin").restore(request)).isEqualTo(restored);
    assertThat(row()).isEqualTo(before);
    assertThat(
            jdbc.queryForMap(
                "SELECT * FROM hub.p_stock_restorations WHERE order_id=?", request.orderId()))
        .isEqualTo(receipt);
    // Replaying a receipt never depends on the current stock being active.
    jdbc.update("UPDATE hub.p_stocks SET deleted_at=NOW(), deleted_by='alice' WHERE id=?", stock);
    assertThat(client.restore(request)).isEqualTo(restored);
  }

  @Test
  void theSameRestorationKeyRejectsAnyDifferentHubProductOrQuantity() throws Exception {
    var client = client(HubSecurityTestTokens.ORDERS, "alice");
    client.deduct(new Deduction(UUID.randomUUID(), hub, product, 10));
    UUID order = UUID.randomUUID();
    client.restore(new Deduction(order, hub, product, 3));
    var before = row();
    for (var changed :
        List.of(
            new Deduction(order, hub, product, 4),
            new Deduction(order, UUID.randomUUID(), product, 3),
            new Deduction(order, hub, UUID.randomUUID(), 3))) {
      var response = restore(changed, HubSecurityTestTokens.ORDERS, "alice");
      assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
      assertThat(mapper.readTree(response.body()).path("code").asText())
          .isEqualTo("STOCK_RESTORATION_CONFLICT");
    }
    assertThat(row()).isEqualTo(before);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM hub.p_stock_restorations", Long.class))
        .isEqualTo(1);
  }

  @Test
  void concurrentRestorationsOfOneOrderIncreaseStockOnlyOnce() throws Exception {
    var client = client(HubSecurityTestTokens.ORDERS, "alice");
    client.deduct(new Deduction(UUID.randomUUID(), hub, product, 10));
    var request = new Deduction(UUID.randomUUID(), hub, product, 3);
    var start = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var calls = new java.util.ArrayList<java.util.concurrent.Future<HttpResponse<String>>>();
      for (int i = 0; i < 8; i++)
        calls.add(
            executor.submit(
                () -> {
                  assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                  return restore(request, HubSecurityTestTokens.ORDERS, "alice");
                }));
      start.countDown();
      for (var call : calls) assertThat(call.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
    }
    assertThat(row().get("quantity")).isEqualTo(Long.MAX_VALUE - 7);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM hub.p_stock_restorations", Long.class))
        .isEqualTo(1);
  }

  @Test
  void conflictingConcurrentRequestsOnDifferentStockRowsHaveOneWinner() throws Exception {
    UUID otherProduct = UUID.randomUUID();
    jdbc.update("UPDATE hub.p_stocks SET quantity=10 WHERE id=?", stock);
    jdbc.update(
        "INSERT INTO hub.p_stocks(id,hub_id,product_id,quantity,created_at,created_by,updated_at,updated_by) VALUES(?,?,?,10,NOW(),'alice',NOW(),'alice')",
        UUID.randomUUID(),
        hub,
        otherProduct);
    UUID order = UUID.randomUUID();
    var start = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(
              () -> {
                start.await();
                return restore(
                    new Deduction(order, hub, product, 3), HubSecurityTestTokens.ORDERS, "alice");
              });
      var second =
          executor.submit(
              () -> {
                start.await();
                return restore(
                    new Deduction(order, hub, otherProduct, 4),
                    HubSecurityTestTokens.ORDERS,
                    "alice");
              });
      start.countDown();
      assertThat(
              List.of(
                  first.get(15, TimeUnit.SECONDS).statusCode(),
                  second.get(15, TimeUnit.SECONDS).statusCode()))
          .containsExactlyInAnyOrder(200, 409);
    }
    var receipt =
        jdbc.queryForMap("SELECT * FROM hub.p_stock_restorations WHERE order_id=?", order);
    long restored = (Long) receipt.get("quantity");
    assertThat(jdbc.queryForObject("SELECT sum(quantity) FROM hub.p_stocks", Long.class))
        .isEqualTo(20 + restored);
    assertThat(
            jdbc.queryForObject("SELECT count(*) FROM hub.p_stocks WHERE quantity=10", Long.class))
        .isEqualTo(1);
  }

  @Test
  void receiptWriteFailureRollsBackStockAndAllowsALaterRestoration() throws Exception {
    jdbc.update("UPDATE hub.p_stocks SET quantity=10 WHERE id=?", stock);
    var request = new Deduction(UUID.randomUUID(), hub, product, 3);
    jdbc.execute(
        "CREATE FUNCTION hub.reject_receipt() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture'; END $$");
    jdbc.execute(
        "CREATE TRIGGER reject_receipt BEFORE INSERT ON hub.p_stock_restorations FOR EACH ROW EXECUTE FUNCTION hub.reject_receipt()");
    var before = row();
    try {
      assertThat(restore(request, HubSecurityTestTokens.ORDERS, "alice").statusCode())
          .isEqualTo(500);
      assertThat(row()).isEqualTo(before);
      assertThat(jdbc.queryForObject("SELECT count(*) FROM hub.p_stock_restorations", Long.class))
          .isZero();
    } finally {
      jdbc.execute("DROP TRIGGER reject_receipt ON hub.p_stock_restorations");
      jdbc.execute("DROP FUNCTION hub.reject_receipt()");
    }
    assertThat(restore(request, HubSecurityTestTokens.ORDERS, "alice").statusCode()).isEqualTo(200);
    assertThat(row().get("quantity")).isEqualTo(13L);
  }

  @Test
  void restorationRequiresOrdersServiceAuthenticationAndAuditActor() throws Exception {
    var before = row();
    var request = new Deduction(UUID.randomUUID(), hub, product, 3);
    assertThat(restore(request, "", "alice").statusCode()).isEqualTo(401);
    assertThat(restore(request, HubSecurityTestTokens.COMPANY, "alice").statusCode())
        .isEqualTo(403);
    assertThat(restore(request, HubSecurityTestTokens.ORDERS, "").statusCode()).isEqualTo(401);
    assertThat(row()).isEqualTo(before);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM hub.p_stock_restorations", Long.class))
        .isZero();
  }

  private HttpResponse<String> restore(Deduction request, String token, String actor)
      throws Exception {
    return post(
        "/internal/stocks/restorations",
        mapper.writeValueAsString(request),
        Map.of("X-Service-Token", token, "X-Actor-Username", actor));
  }

  @Test
  void rawResponseUses200AndOnlyTheFourOrdersStockFields() throws Exception {
    var response =
        post(
            "/internal/stocks/deductions",
            mapper.writeValueAsString(new Deduction(UUID.randomUUID(), hub, product, 3)),
            Map.of("X-Service-Token", HubSecurityTestTokens.ORDERS, "X-Actor-Username", "alice"));
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(mapper.readTree(response.body()))
        .isEqualTo(
            mapper.readTree(
                mapper.writeValueAsString(
                    Map.of(
                        "data",
                        Map.of(
                            "id", stock,
                            "hub_id", hub,
                            "product_id", product,
                            "quantity", Long.MAX_VALUE - 3)))));
  }

  @Test
  void exhaustedStockReturns409AndDoesNotChangeQuantityOrAudit() throws Exception {
    var client = client(HubSecurityTestTokens.ORDERS, "alice");
    client.deduct(new Deduction(UUID.randomUUID(), hub, product, Long.MAX_VALUE));
    var before = row();
    assertFailure(
        client, new Deduction(UUID.randomUUID(), hub, product, 1), 409, "INSUFFICIENT_STOCK");
    assertThat(row()).isEqualTo(before);
  }

  @Test
  void missingOrDeletedStockReturns404WithoutChangingOtherStock() throws Exception {
    var client = client(HubSecurityTestTokens.ORDERS, "alice");
    assertFailure(
        client,
        new Deduction(UUID.randomUUID(), hub, UUID.randomUUID(), 1),
        404,
        "STOCK_NOT_FOUND");
    assertThat(row().get("quantity")).isEqualTo(Long.MAX_VALUE);
    jdbc.update("UPDATE hub.p_stocks SET deleted_at=NOW(), deleted_by='alice' WHERE id=?", stock);
    var before = row();
    assertFailure(
        client, new Deduction(UUID.randomUUID(), hub, product, 1), 404, "STOCK_NOT_FOUND");
    assertThat(row()).isEqualTo(before);
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing-token", "wrong-service", "missing-actor"})
  void invalidServiceAuthenticationCannotChangeStock(String scenario) throws Exception {
    String token =
        switch (scenario) {
          case "missing-token" -> "";
          case "wrong-service" -> HubSecurityTestTokens.COMPANY;
          default -> HubSecurityTestTokens.ORDERS;
        };
    String actor = scenario.equals("missing-actor") ? "" : "alice";
    var before = row();
    int expected = scenario.equals("wrong-service") ? 403 : 401;
    var request = new Deduction(UUID.randomUUID(), hub, product, 1);
    assertThatThrownBy(() -> client(token, actor).deduct(request))
        .isInstanceOfSatisfying(
            FeignException.class, exception -> assertThat(exception.status()).isEqualTo(expected));
    // HttpURLConnection may omit a 401 body in streaming mode; check the server response directly.
    var headers = new HashMap<String, String>();
    if (!token.isBlank()) headers.put("X-Service-Token", token);
    if (!actor.isBlank()) headers.put("X-Actor-Username", actor);
    var response = post("/internal/stocks/deductions", mapper.writeValueAsString(request), headers);
    assertThat(response.statusCode()).isEqualTo(expected);
    assertThat(mapper.readTree(response.body()).path("code").asText())
        .isEqualTo(expected == 403 ? "FORBIDDEN" : "UNAUTHORIZED");
    assertThat(row()).isEqualTo(before);
  }

  @Test
  void repeatedOrderIdDeductsAgainAsDocumentedByOrders() {
    var client = client(HubSecurityTestTokens.ORDERS, "alice");
    var request = new Deduction(UUID.randomUUID(), hub, product, 3);
    assertThat(client.deduct(request).data().quantity()).isEqualTo(Long.MAX_VALUE - 3);
    assertThat(client.deduct(request).data().quantity()).isEqualTo(Long.MAX_VALUE - 6);
    assertThat(row().get("quantity")).isEqualTo(Long.MAX_VALUE - 6);
  }

  private OrdersStockClient client(String token, String actor) {
    return Feign.builder()
        .contract(new SpringMvcContract())
        .encoder(new SpringEncoder(converters))
        .decoder(new ResponseEntityDecoder(new SpringDecoder(converters)))
        .retryer(Retryer.NEVER_RETRY)
        .requestInterceptor(
            template -> {
              if (!token.isBlank()) template.header("X-Service-Token", token);
              if (!actor.isBlank()) template.header("X-Actor-Username", actor);
            })
        .target(OrdersStockClient.class, "http://127.0.0.1:" + port);
  }

  private void assertFailure(OrdersStockClient client, Deduction request, int status, String code) {
    assertThatThrownBy(() -> client.deduct(request))
        .isInstanceOfSatisfying(
            FeignException.class,
            exception -> {
              assertThat(exception.status()).isEqualTo(status);
              assertThat(exception.contentUTF8()).contains("\"code\":\"" + code + "\"");
            });
  }

  private Map<String, Object> row() {
    return jdbc.queryForMap("SELECT * FROM hub.p_stocks WHERE id=?", stock);
  }

  private HttpResponse<String> post(String path, String body, Map<String, String> headers)
      throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    headers.forEach(builder::header);
    try (var http = HttpClient.newHttpClient()) {
      return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
  }

  // Consumer contract: Orders PR #52, 3b0ad69, StockClient and OrderClientTypes.
  interface OrdersStockClient {
    @PostMapping("/internal/stocks/deductions")
    ApiResponse<Stock> deduct(@RequestBody Deduction request);

    @PostMapping("/internal/stocks/restorations")
    ApiResponse<Stock> restore(@RequestBody Deduction request);
  }

  record Deduction(UUID orderId, UUID hubId, UUID productId, long quantity) {}

  record Stock(UUID id, UUID hubId, UUID productId, Long quantity) {}
}
