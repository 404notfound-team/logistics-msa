package com.notfound.logistics.orders.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notfound.logistics.orders.OrdersApplication;
import com.notfound.logistics.orders.application.service.OrderService;
import com.notfound.logistics.orders.domain.repository.OrderRepository;
import com.notfound.logistics.orders.support.LocalOrderStub;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(
    classes = OrdersApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "eureka.client.enabled=false",
      "management.tracing.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.properties.hibernate.default_schema=public",
      "orders.recovery.enabled=false",
      "orders.clients.read-timeout-ms=1000",
      "springdoc.api-docs.enabled=true"
    })
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderApiIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> DATABASE =
      new PostgreSQLContainer<>("postgres:18-alpine")
          .withLabel("com.notfound.logistics.task", "orders-complete-01a13e9f");

  static final LocalOrderStub STUB = new LocalOrderStub();
  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired OrderRepository repository;
  @Autowired OrderService service;
  @Autowired JdbcTemplate jdbc;
  String alice;
  String bob;
  String admin;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    String url = STUB.start();
    registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
    registry.add("spring.datasource.username", DATABASE::getUsername);
    registry.add("spring.datasource.password", DATABASE::getPassword);
    registry.add("orders.clients.company-url", () -> url);
    registry.add("orders.clients.stock-url", () -> url);
    registry.add("orders.clients.delivery-url", () -> url);
    registry.add("orders.clients.service-token", () -> LocalOrderStub.SERVICE_TOKEN);
    registry.add("orders.jwt.secret", () -> LocalOrderStub.SECRET);
    registry.add("slack.enabled", () -> true);
    registry.add("slack.webhook-url", () -> url + "/__stub/slack");
  }

  @AfterAll
  static void stopStub() {
    STUB.close();
  }

  @BeforeEach
  void reset() {
    STUB.awaitIdle();
    STUB.control(Map.of("action", "reset"));
    jdbc.execute("TRUNCATE TABLE p_orders");
    alice = STUB.token("alice", "USER");
    bob = STUB.token("bob", "USER");
    admin = STUB.token("admin", "MASTER");
  }

  @Test
  void confirmsAndReplaysWithoutRepeatingSideEffectsAndPreservesSnapshots() throws Exception {
    UUID key = UUID.randomUUID();
    var first = create(key, body(), alice);
    assertStatus(first, 201);
    UUID id = id(first);
    assertThat(json(first).at("/data/status").asText()).isEqualTo("CONFIRMED");
    assertThat(first.headers().firstValue("Location")).contains("/api/orders/" + id);
    STUB.control(Map.of("action", "product", "name", "변경된 상품", "price", "20000.00"));
    STUB.control(
        Map.of(
            "action",
            "company_hub",
            "company_id",
            LocalOrderStub.SUPPLIER,
            "hub_id",
            "00000000-0000-0000-0000-000000000099"));
    assertStatus(create(key, body(), alice), 200);
    var details = json(request("GET", "/api/orders/" + id, null, alice, null)).path("data");
    assertThat(details.path("product_name").asText()).isEqualTo("테스트 상품");
    assertThat(details.path("unit_price").decimalValue()).isEqualByComparingTo("10000.00");
    assertThat(details.path("total_price").decimalValue()).isEqualByComparingTo("30000.00");
    assertThat(details.path("departure_hub_id").asText()).endsWith("000000000002");
    assertThat(details.has("recovery_action")).isFalse();
    assertThat(repository.count()).isEqualTo(1);
    assertInventory(997, 1, 1);
    assertThat(STUB.state().at("/counts/delivery.create").asInt()).isEqualTo(1);
  }

  @Test
  void verifiedPrincipalControlsAuditAndInternalRequestsDespiteForgedIdentityHeaders()
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/orders"))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + alice)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .header("X-Actor-Username", "admin")
            .header("X-User-Role", "MASTER")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body())))
            .build();
    try (var client = HttpClient.newHttpClient()) {
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      assertStatus(response, 201);
      var saved = repository.findById(id(response)).orElseThrow();
      assertThat(saved.getCreatedBy()).isEqualTo("alice");
      assertThat(saved.getUpdatedBy()).isEqualTo("alice");
    }
    var sent = STUB.state().path("actors");
    assertThat(sent.path("company.get").asText()).isEqualTo("alice");
    assertThat(sent.path("stock.deduct").asText()).isEqualTo("alice");
    assertThat(sent.path("delivery.create").asText()).isEqualTo("alice");
  }

  @Test
  void validatesBodyHeaderTypesAndLengthsBeforeSideEffects() throws Exception {
    for (Object quantity : List.of(0, -1, 1.5, "9223372036854775808")) {
      var body = body();
      body.put("quantity", quantity);
      assertStatus(create(UUID.randomUUID(), body, alice), 400);
    }
    var missing = body();
    missing.remove("quantity");
    assertStatus(create(UUID.randomUUID(), missing, alice), 400);
    var invalid = body();
    invalid.put("receiver_company_id", "invalid");
    assertStatus(create(UUID.randomUUID(), invalid, alice), 400);
    invalid = body();
    invalid.put("delivery_address", " ");
    assertStatus(create(UUID.randomUUID(), invalid, alice), 400);
    invalid = body();
    invalid.put("recipient_name", "x".repeat(101));
    var rejected = create(UUID.randomUUID(), invalid, alice);
    assertStatus(rejected, 400);
    assertThat(json(rejected).path("field_errors").findValuesAsText("field"))
        .contains("recipient_name");
    assertStatus(request("POST", "/api/orders", body(), alice, null), 400);
    assertStatus(request("POST", "/api/orders", body(), alice, "invalid"), 400);
    assertThat(repository.count()).isZero();
    assertInventory(1000, 0, 0);
  }

  @Test
  void rejectsUnauthenticatedExpiredAndTamperedTokensAndChecksOwnership() throws Exception {
    assertStatus(create(UUID.randomUUID(), body(), null), 401);
    assertStatus(create(UUID.randomUUID(), body(), alice + "tampered"), 401);
    String expired =
        STUB.call("POST", "/__stub/token", Map.of("username", "alice", "lifetime", -60))
            .path("access_token")
            .asText();
    assertStatus(create(UUID.randomUUID(), body(), expired), 401);
    UUID id = id(create(UUID.randomUUID(), body(), alice));
    assertStatus(request("GET", "/api/orders/" + id, null, bob, null), 403);
    assertStatus(request("GET", "/api/orders/" + id, null, admin, null), 200);
    assertInventory(997, 1, 1);
  }

  @Test
  void rejectsDifferentBodyForTheSameKey() throws Exception {
    UUID key = UUID.randomUUID();
    assertStatus(create(key, body(), alice), 201);
    var changed = body();
    changed.put("quantity", 4);
    var result = create(key, changed, alice);
    assertStatus(result, 409);
    assertThat(json(result).path("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    assertThat(repository.count()).isEqualTo(1);
    assertInventory(997, 1, 1);
  }

  @Test
  void concurrentIdenticalRequestsCreateOneOrderDeductionDeliveryAndNotification()
      throws Exception {
    UUID key = UUID.randomUUID();
    var calls = new ArrayList<java.util.concurrent.Future<HttpResponse<String>>>();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < 8; i++) calls.add(executor.submit(() -> create(key, body(), alice)));
      var responses = new ArrayList<HttpResponse<String>>();
      for (var call : calls) responses.add(call.get(15, TimeUnit.SECONDS));
      assertThat(responses.stream().filter(value -> value.statusCode() == 201)).hasSize(1);
      var ids = new ArrayList<UUID>();
      for (var response : responses) {
        assertThat(response.statusCode()).as(response.body()).isIn(200, 201, 202);
        ids.add(id(response));
      }
      assertThat(ids).containsOnly(ids.getFirst());
    }
    assertThat(repository.count()).isEqualTo(1);
    assertInventory(997, 1, 1);
  }

  @Test
  void insufficientStockFailsWithoutDeductionAndReplaysFailedOrder() throws Exception {
    STUB.control(Map.of("action", "stock", "quantity", 2));
    UUID key = UUID.randomUUID();
    var failure = create(key, body(), alice);
    assertStatus(failure, 409);
    assertThat(json(failure).path("code").asText()).isEqualTo("INSUFFICIENT_STOCK");
    var replay = create(key, body(), alice);
    assertStatus(replay, 200);
    assertThat(json(replay).at("/data/status").asText()).isEqualTo("FAILED");
    assertInventory(2, 0, 0);
    assertThat(STUB.state().at("/counts/stock.deduct").asInt()).isEqualTo(1);
  }

  @Test
  void definiteDeliveryFailureDoesNotRestoreOrRepeatDeduction() throws Exception {
    fault("delivery.create", Map.of("status", 404, "code", "HUB_ROUTE_NOT_FOUND"));
    UUID key = UUID.randomUUID();
    var failure = create(key, body(), alice);
    assertStatus(failure, 502);
    assertThat(json(failure).path("code").asText()).isEqualTo("DELIVERY_CREATION_FAILED");
    assertInventory(997, 0, 0);
    assertThat(repository.findReservedKey("alice", key).orElseThrow().getStatus().name())
        .isEqualTo("FAILED");
    assertStatus(create(key, body(), alice), 200);
    assertThat(STUB.state().at("/counts/stock.deduct").asInt()).isEqualTo(1);
  }

  @Test
  void timedOutDeliveryCommitIsConfirmedByRecoveryUsingOriginalSnapshotsAndActor()
      throws Exception {
    fault("delivery.create", Map.of("delay_after_ms", 3000));
    UUID key = UUID.randomUUID();
    var pending = create(key, body(), alice);
    assertStatus(pending, 202);
    UUID id = id(pending);
    assertThat(json(pending).at("/data/status").asText()).isEqualTo("RECOVERY_REQUIRED");
    STUB.control(Map.of("action", "product", "name", "바뀐 상품", "price", "20000.00"));
    STUB.control(
        Map.of(
            "action",
            "company_hub",
            "company_id",
            LocalOrderStub.SUPPLIER,
            "hub_id",
            "00000000-0000-0000-0000-000000000099"));
    assertStatus(create(key, body(), alice), 202);
    service.recover();
    var order = repository.findById(id).orElseThrow();
    assertThat(order.getStatus().name()).isEqualTo("CONFIRMED");
    assertThat(order.getProductName()).isEqualTo("테스트 상품");
    assertThat(order.getCreatedBy()).isEqualTo("alice");
    assertThat(order.getUpdatedBy()).isEqualTo("alice");
    assertThat(order.getRecoveryAction()).isNull();
    assertThat(order.getLastError()).isNull();
    assertStatus(create(key, body(), alice), 200);
    assertInventory(997, 1, 1);
    assertThat(STUB.state().at("/counts/delivery.create").asInt()).isEqualTo(1);
  }

  @Test
  void a404AfterAnUncertainDeliveryMustNotFailTheOrder() throws Exception {
    fault("delivery.create", Map.of("delay_before_ms", 3000));
    var response = create(UUID.randomUUID(), body(), alice);
    assertStatus(response, 202);
    UUID id = id(response);
    fault("delivery.create", Map.of("status", 404, "code", "HUB_ROUTE_NOT_FOUND"));
    service.recover();
    assertThat(repository.findById(id).orElseThrow().getStatus().name())
        .isEqualTo("RECOVERY_REQUIRED");
    assertThat(STUB.state().at("/counts/stock.restore").asInt()).isZero();
    STUB.awaitIdle();
    service.recover();
    assertThat(repository.findById(id).orElseThrow().getStatus().name()).isEqualTo("CONFIRMED");
    assertInventory(997, 1, 1);
  }

  @Test
  void deductionTimeoutAfterCommitNeverRepeatsDeduction() throws Exception {
    assertUncertainDeductionStopsWithoutRetry("delay_after_ms", 997);
  }

  @Test
  void deductionTimeoutBeforeCommitNeverRepeatsDeduction() throws Exception {
    assertUncertainDeductionStopsWithoutRetry("delay_before_ms", 1000);
  }

  @Test
  void malformedDeductionAcknowledgementsFailWithoutDeliveryOrRetry() throws Exception {
    var missingQuantity =
        Map.of(
            "data",
            Map.of(
                "id", "00000000-0000-0000-0000-000000000003",
                "hub_id", "00000000-0000-0000-0000-000000000002",
                "product_id", LocalOrderStub.PRODUCT));
    int attempts = 0;
    for (Object response : java.util.Arrays.asList(null, Map.of(), missingQuantity)) {
      var options = new HashMap<String, Object>();
      options.put("response", response);
      fault("stock.deduct", options);
      UUID key = UUID.randomUUID();
      var failed = create(key, body(), alice);
      assertStatus(failed, 503);
      assertThat(repository.findReservedKey("alice", key).orElseThrow().getStatus().name())
          .isEqualTo("FAILED");
      assertStatus(create(key, body(), alice), 200);
      service.recover();
      attempts++;
      assertInventory(1000 - 3L * attempts, 0, 0);
      assertThat(STUB.state().at("/counts/stock.deduct").asInt()).isEqualTo(attempts);
    }
  }

  @Test
  void interruptedStockPhaseIsNotAutomaticallyDeductedAgain() throws Exception {
    UUID id = id(create(UUID.randomUUID(), body(), alice));
    jdbc.update(
        "UPDATE p_orders SET status='PROCESSING', recovery_action='CREATE', last_error='STOCK_RESULT_UNKNOWN', delivery_id=null WHERE id=?",
        id);
    service.recover();
    service.recover();
    var failed = repository.findById(id).orElseThrow();
    assertThat(failed.getStatus().name()).isEqualTo("FAILED");
    assertThat(failed.getLastError()).isEqualTo("UPSTREAM_UNAVAILABLE");
    assertThat(failed.getRecoveryAction()).isNull();
    assertInventory(997, 1, 1);
    assertThat(STUB.state().at("/counts/stock.deduct").asInt()).isEqualTo(1);
  }

  private void assertUncertainDeductionStopsWithoutRetry(String delay, long immediateStock)
      throws Exception {
    fault("stock.deduct", Map.of(delay, 3000));
    UUID key = UUID.randomUUID();
    var response = create(key, body(), alice);
    assertStatus(response, 503);
    assertThat(json(response).path("code").asText()).isEqualTo("UPSTREAM_UNAVAILABLE");
    var failed = repository.findReservedKey("alice", key).orElseThrow();
    assertThat(failed.getStatus().name()).isEqualTo("FAILED");
    assertInventory(immediateStock, 0, 0);
    assertStatus(create(key, body(), alice), 200);
    service.recover();
    STUB.awaitIdle();
    service.recover();
    assertInventory(997, 0, 0);
    assertThat(STUB.state().at("/counts/stock.deduct").asInt()).isEqualTo(1);
    assertThat(STUB.state().has("operations")).isFalse();
  }

  @Test
  void documentsActualPublicContractsAndSupportsBigintQuantityAndTextNotes() throws Exception {
    STUB.control(Map.of("action", "stock", "quantity", 5000000000L));
    var body = body();
    body.put("quantity", 3000000000L);
    body.put("request_notes", "x".repeat(1000));
    UUID id = id(create(UUID.randomUUID(), body, alice));
    assertThat(repository.findById(id).orElseThrow().getQuantity()).isEqualTo(3000000000L);
    assertThat(repository.findById(id).orElseThrow().getRequestNotes()).hasSize(1000);
    var docs = json(request("GET", "/v3/api-docs", null, null, null));
    assertThat(docs.at("/paths/~1api~1orders/post/responses").has("201")).isTrue();
    assertThat(docs.at("/components/securitySchemes/bearerAuth/scheme").asText())
        .isEqualTo("bearer");
    assertThat(docs.at("/paths/~1api~1orders/post/parameters").findValuesAsText("name"))
        .containsExactly("Idempotency-Key");
    assertThat(docs.path("paths").toString()).doesNotContain("/internal/");
    assertThat(
            jdbc.queryForObject(
                "select numeric_precision from information_schema.columns where table_name='p_orders' and column_name='unit_price'",
                Integer.class))
        .isEqualTo(15);
    assertThat(
            jdbc.queryForObject(
                "select numeric_scale from information_schema.columns where table_name='p_orders' and column_name='unit_price'",
                Integer.class))
        .isEqualTo(2);
  }

  @Test
  void validatesReferenceExistenceAndSupplierOwnership() throws Exception {
    var invalid = body();
    invalid.put("supplier_company_id", UUID.randomUUID().toString());
    assertStatus(create(UUID.randomUUID(), invalid, alice), 404);
    invalid = body();
    invalid.put("product_id", UUID.randomUUID().toString());
    var missingProduct = create(UUID.randomUUID(), invalid, alice);
    assertStatus(missingProduct, 404);
    assertThat(json(missingProduct).path("code").asText()).isEqualTo("PRODUCT_NOT_FOUND");
    invalid = body();
    invalid.put("supplier_company_id", LocalOrderStub.RECEIVER);
    assertStatus(create(UUID.randomUUID(), invalid, alice), 409);
    assertThat(repository.count()).isZero();
  }

  @Test
  void notificationFailureDoesNotUndoConfirmationOrRetryOnReplay() throws Exception {
    fault("slack", Map.of("status", 500, "code", "INTERNAL_ERROR"));
    UUID key = UUID.randomUUID();
    var response = create(key, body(), alice);
    assertStatus(response, 201);
    assertStatus(create(key, body(), alice), 200);
    assertThat(repository.findById(id(response)).orElseThrow().getStatus().name())
        .isEqualTo("CONFIRMED");
    assertThat(STUB.state().at("/counts/slack").asInt()).isEqualTo(1);
    assertThat(STUB.state().at("/stocks/0/quantity").asLong()).isEqualTo(997);
  }

  @Test
  void detailExcludesDeletedOrdersAndPreservesAccessBoundaries() throws Exception {
    UUID id = id(create(UUID.randomUUID(), body(), alice));
    assertStatus(request("GET", "/api/orders/" + UUID.randomUUID(), null, alice, null), 404);
    jdbc.update(
        "UPDATE p_orders SET deleted_at=current_timestamp, deleted_by='admin' WHERE id=?", id);
    assertStatus(request("GET", "/api/orders/" + id, null, alice, null), 404);
    assertStatus(request("GET", "/api/orders/" + id, null, admin, null), 404);
  }

  private Map<String, Object> body() {
    return new HashMap<>(
        Map.of(
            "receiver_company_id",
            LocalOrderStub.RECEIVER,
            "supplier_company_id",
            LocalOrderStub.SUPPLIER,
            "product_id",
            LocalOrderStub.PRODUCT,
            "quantity",
            3,
            "delivery_address",
            "서울시 송파구 테스트 주소",
            "recipient_name",
            "수령인"));
  }

  private HttpResponse<String> create(UUID key, Object body, String token) throws Exception {
    return request("POST", "/api/orders", body, token, key.toString());
  }

  private HttpResponse<String> request(
      String method, String path, Object body, String token, String key) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json");
    if (token != null) request.header("Authorization", "Bearer " + token);
    if (key != null) request.header("Idempotency-Key", key);
    request.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
    try (var client = HttpClient.newHttpClient()) {
      return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
  }

  private JsonNode json(HttpResponse<String> response) throws Exception {
    return mapper.readTree(response.body());
  }

  private UUID id(HttpResponse<String> response) throws Exception {
    assertThat(response.statusCode()).as(response.body()).isIn(200, 201, 202);
    return UUID.fromString(json(response).at("/data/order_id").asText());
  }

  private void assertStatus(HttpResponse<String> response, int expected) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(expected);
  }

  private void assertInventory(long stock, int deliveryCount, int notificationCount) {
    var state = STUB.state();
    assertThat(state.at("/stocks/0/quantity").asLong()).isEqualTo(stock);
    assertThat(state.path("deliveries")).hasSize(deliveryCount);
    assertThat(state.path("notifications")).hasSize(notificationCount);
  }

  private void fault(String operation, Map<String, Object> options) {
    var fault = new HashMap<String, Object>(options);
    fault.put("action", "fault");
    fault.put("operation", operation);
    fault.put("remaining", 1);
    STUB.control(fault);
  }
}
