package com.notfound.logistics.orders.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notfound.logistics.orders.OrdersApplication;
import com.notfound.logistics.orders.application.service.ActiveOrderProcessing;
import com.notfound.logistics.orders.application.service.ActiveOrderProcessing.Operation;
import com.notfound.logistics.orders.application.service.OrderService;
import com.notfound.logistics.orders.domain.repository.OrderRepository;
import com.notfound.logistics.orders.support.OrderClientFixture;
import com.notfound.logistics.orders.support.OrderClientTestConfiguration;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
@Import(OrderClientTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderApiIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> DATABASE =
      new PostgreSQLContainer<>("postgres:18-alpine")
          .withLabel("com.notfound.logistics.task", "orders-complete-01a13e9f");

  @Autowired OrderClientFixture fixture;
  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @MockitoSpyBean OrderRepository repository;
  @MockitoSpyBean ActiveOrderProcessing active;
  @Autowired OrderService service;
  @Autowired JdbcTemplate jdbc;
  String alice;
  String bob;
  String admin;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
    registry.add("spring.datasource.username", DATABASE::getUsername);
    registry.add("spring.datasource.password", DATABASE::getPassword);
    registry.add("orders.clients.service-token", () -> OrderClientFixture.SERVICE_TOKEN);
    registry.add("orders.jwt.secret", () -> OrderClientFixture.SECRET);
  }

  @BeforeEach
  void reset() {
    fixture.awaitIdle();
    fixture.control(Map.of("action", "reset"));
    jdbc.execute("TRUNCATE TABLE p_orders");
    alice = fixture.token("alice", "USER");
    bob = fixture.token("bob", "USER");
    admin = fixture.token("admin", "MASTER");
  }

  @Test
  void confirmsAndReplaysWithoutRepeatingSideEffectsAndPreservesSnapshots() throws Exception {
    UUID key = UUID.randomUUID();
    var first = create(key, body(), alice);
    assertStatus(first, 201);
    UUID id = id(first);
    assertThat(json(first).at("/data/status").asText()).isEqualTo("CONFIRMED");
    assertThat(fixture.state().at("/deliveries/0/status").asText()).isEqualTo("HUB_WAITING");
    assertThat(first.headers().firstValue("Location")).contains("/api/orders/" + id);
    fixture.control(Map.of("action", "product", "name", "변경된 상품", "price", "20000.00"));
    fixture.control(
        Map.of(
            "action",
            "company_hub",
            "company_id",
            OrderClientFixture.SUPPLIER,
            "hub_id",
            "00000000-0000-0000-0000-000000000099"));
    assertStatus(create(key, body(), alice), 200);
    var details = json(request("GET", "/api/orders/" + id, null, alice, null)).path("data");
    assertThat(details.path("product_name").asText()).isEqualTo("테스트 상품");
    assertThat(details.path("unit_price").decimalValue()).isEqualByComparingTo("10000.00");
    assertThat(details.path("total_price").decimalValue()).isEqualByComparingTo("30000.00");
    assertThat(details.path("departure_hub_id").asText()).endsWith("000000000002");
    assertThat(details.has("recovery_phase")).isFalse();
    assertThat(repository.count()).isEqualTo(1);
    assertInventory(997, 1, 1);
    assertThat(fixture.state().at("/counts/delivery.create").asInt()).isEqualTo(1);
  }

  @Test
  void acceptsTheFullCompanyPriceRangeAndReturnsAnExactIntegerTotal() throws Exception {
    fixture.control(Map.of("action", "product", "price", Long.toString(Long.MAX_VALUE)));
    UUID orderId = id(create(UUID.randomUUID(), body(), alice));
    var details = json(request("GET", "/api/orders/" + orderId, null, alice, null)).path("data");
    assertThat(details.path("unit_price").isIntegralNumber()).isTrue();
    assertThat(details.path("unit_price").longValue()).isEqualTo(Long.MAX_VALUE);
    assertThat(details.path("total_price").bigIntegerValue())
        .isEqualTo(
            java.math.BigInteger.valueOf(Long.MAX_VALUE).multiply(java.math.BigInteger.valueOf(3)));
    assertThat(
            jdbc.queryForObject("select unit_price from p_orders where id=?", Long.class, orderId))
        .isEqualTo(Long.MAX_VALUE);
  }

  @ParameterizedTest
  @ValueSource(strings = {"-1", "0.01", "9223372036854775808"})
  void rejectsPricesOutsideTheCompanyIntegerContractBeforeSideEffects(String price)
      throws Exception {
    fixture.control(Map.of("action", "product", "price", price));
    assertStatus(create(UUID.randomUUID(), body(), alice), 503);
    assertThat(repository.count()).isZero();
    assertInventory(1000, 0, 0);
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
    var sent = fixture.state().path("actors");
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
    String expired = OrderClientFixture.token("alice", "USER", -60);
    assertStatus(create(UUID.randomUUID(), body(), expired), 401);
    UUID id = id(create(UUID.randomUUID(), body(), alice));
    assertStatus(request("GET", "/api/orders/" + id, null, bob, null), 403);
    assertStatus(request("POST", "/api/orders/" + id + "/cancel", null, bob, null), 403);
    assertStatus(request("DELETE", "/api/orders/" + id, null, alice, null), 403);
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
    fixture.control(Map.of("action", "stock", "quantity", 2));
    UUID key = UUID.randomUUID();
    var failure = create(key, body(), alice);
    assertStatus(failure, 409);
    assertThat(json(failure).path("code").asText()).isEqualTo("INSUFFICIENT_STOCK");
    var replay = create(key, body(), alice);
    assertStatus(replay, 200);
    assertThat(json(replay).at("/data/status").asText()).isEqualTo("FAILED");
    assertInventory(2, 0, 0);
    assertThat(fixture.state().at("/counts/stock.deduct").asInt()).isEqualTo(1);
  }

  @Test
  void definiteDeliveryFailureRestoresStockBeforeFailingAndDoesNotRepeatDeduction()
      throws Exception {
    fault("delivery.create", Map.of("status", 404, "code", "HUB_ROUTE_NOT_FOUND"));
    UUID key = UUID.randomUUID();
    var failure = create(key, body(), alice);
    assertStatus(failure, 502);
    assertThat(json(failure).path("code").asText()).isEqualTo("DELIVERY_CREATION_FAILED");
    assertInventory(1000, 0, 0);
    assertThat(fixture.state().at("/counts/stock.restore").asInt()).isEqualTo(1);
    assertThat(repository.findReservedKey("alice", key).orElseThrow().getStatus().name())
        .isEqualTo("FAILED");
    assertStatus(create(key, body(), alice), 200);
    assertThat(fixture.state().at("/counts/stock.deduct").asInt()).isEqualTo(1);
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
    fixture.control(Map.of("action", "product", "name", "바뀐 상품", "price", "20000.00"));
    fixture.control(
        Map.of(
            "action",
            "company_hub",
            "company_id",
            OrderClientFixture.SUPPLIER,
            "hub_id",
            "00000000-0000-0000-0000-000000000099"));
    assertStatus(create(key, body(), alice), 202);
    service.recover();
    var order = repository.findById(id).orElseThrow();
    assertThat(order.getStatus().name()).isEqualTo("CONFIRMED");
    assertThat(order.getProductName()).isEqualTo("테스트 상품");
    assertThat(order.getCreatedBy()).isEqualTo("alice");
    assertThat(order.getUpdatedBy()).isEqualTo("alice");
    assertThat(order.getRecoveryPhase()).isNull();
    assertThat(order.getLastError()).isNull();
    assertStatus(create(key, body(), alice), 200);
    assertInventory(997, 1, 1);
    assertThat(fixture.state().at("/counts/delivery.create").asInt()).isEqualTo(1);
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
    assertThat(fixture.state().at("/counts/stock.restore").asInt()).isZero();
    fixture.awaitIdle();
    service.recover();
    assertThat(repository.findById(id).orElseThrow().getStatus().name()).isEqualTo("CONFIRMED");
    assertInventory(997, 1, 1);
  }

  @Test
  void deductionTimeoutAfterCommitIsRecoveredByIdempotentRetry() throws Exception {
    assertUncertainDeductionRecoversWithoutAnotherEffect("delay_after_ms", 997);
  }

  @Test
  void deductionTimeoutBeforeCommitIsRecoveredByIdempotentRetry() throws Exception {
    assertUncertainDeductionRecoversWithoutAnotherEffect("delay_before_ms", 1000);
  }

  @Test
  void malformedDeductionAcknowledgementsAreRecoveredWithoutAnotherStockEffect() throws Exception {
    var missingQuantity =
        Map.of(
            "data",
            Map.of(
                "id", "00000000-0000-0000-0000-000000000003",
                "hub_id", "00000000-0000-0000-0000-000000000002",
                "product_id", OrderClientFixture.PRODUCT));
    int attempts = 0;
    for (Object response : java.util.Arrays.asList(null, Map.of(), missingQuantity)) {
      var options = new HashMap<String, Object>();
      options.put("response", response);
      fault("stock.deduct", options);
      UUID key = UUID.randomUUID();
      var failed = create(key, body(), alice);
      assertStatus(failed, 202);
      assertThat(repository.findReservedKey("alice", key).orElseThrow().getStatus().name())
          .isEqualTo("RECOVERY_REQUIRED");
      assertStatus(create(key, body(), alice), 202);
      service.recover();
      attempts++;
      assertInventory(1000 - 3L * attempts, attempts, attempts);
      assertThat(fixture.state().at("/counts/stock.deduct").asInt()).isEqualTo(attempts * 2);
      assertThat(repository.findReservedKey("alice", key).orElseThrow().getStatus().name())
          .isEqualTo("CONFIRMED");
    }
  }

  @Test
  void interruptedStockPhaseReplaysItsDeductionAndCompletes() throws Exception {
    fault("stock.deduct", Map.of("response", Map.of()));
    UUID id = id(create(UUID.randomUUID(), body(), alice));
    jdbc.update(
        "UPDATE p_orders SET status='PROCESSING', last_error='STOCK_RESULT_UNKNOWN', delivery_id=null WHERE id=?",
        id);
    service.recover();
    service.recover();
    var failed = repository.findById(id).orElseThrow();
    assertThat(failed.getStatus().name()).isEqualTo("CONFIRMED");
    assertThat(failed.getLastError()).isNull();
    assertThat(failed.getRecoveryPhase()).isNull();
    assertInventory(997, 1, 1);
    assertThat(fixture.state().at("/counts/stock.deduct").asInt()).isEqualTo(2);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void malformedDeliveryAcknowledgementIsRecheckedWithoutAnotherDeductionOrCreation(
      boolean nullEnvelope) throws Exception {
    var options = new HashMap<String, Object>();
    options.put("response", nullEnvelope ? null : Map.of());
    fault("delivery.create", options);
    UUID id = id(create(UUID.randomUUID(), body(), alice));
    assertThat(repository.findById(id).orElseThrow().getStatus().name())
        .isEqualTo("RECOVERY_REQUIRED");
    service.recover();
    assertThat(repository.findById(id).orElseThrow().getStatus().name()).isEqualTo("CONFIRMED");
    assertThat(fixture.state().at("/counts/stock.deduct").asInt()).isEqualTo(1);
    assertThat(fixture.state().at("/counts/delivery.create").asInt()).isEqualTo(1);
    assertInventory(997, 1, 1);
  }

  @Test
  void nullDeliveryLookupRemainsUnknownUntilAValidResultIsReceived() throws Exception {
    fault("delivery.create", Map.of("response", Map.of()));
    UUID id = id(create(UUID.randomUUID(), body(), alice));
    var options = new HashMap<String, Object>();
    options.put("response", null);
    fault("delivery.get", options);
    service.recover();
    var pending = repository.findById(id).orElseThrow();
    assertThat(pending.getStatus().name()).isEqualTo("RECOVERY_REQUIRED");
    assertThat(pending.getRecoveryAttempts()).isEqualTo(2);
    service.recover();
    assertThat(repository.findById(id).orElseThrow().getStatus().name()).isEqualTo("CONFIRMED");
    assertThat(fixture.state().at("/counts/delivery.create").asInt()).isEqualTo(1);
    assertInventory(997, 1, 1);
  }

  @Test
  void nullCancellationAcknowledgementIsRecoveredBeforeRestoringStock() throws Exception {
    UUID id = id(create(UUID.randomUUID(), body(), alice));
    var options = new HashMap<String, Object>();
    options.put("response", null);
    fault("delivery.cancel", options);
    assertStatus(request("POST", "/api/orders/" + id + "/cancel", null, admin, null), 202);
    assertThat(fixture.state().at("/counts/stock.restore").asInt()).isZero();
    service.recover();
    assertThat(repository.findById(id).orElseThrow().getStatus().name()).isEqualTo("CANCELLED");
    assertThat(fixture.state().at("/counts/stock.restore").asInt()).isEqualTo(1);
    assertInventory(1000, 1, 1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"company.get", "product.get"})
  void nullReferenceLookupIsUnavailableBeforeAnOrderOrStockEffectIsCreated(String operation)
      throws Exception {
    var options = new HashMap<String, Object>();
    options.put("response", null);
    fault(operation, options);
    assertStatus(create(UUID.randomUUID(), body(), alice), 503);
    assertThat(repository.count()).isZero();
    assertInventory(1000, 0, 0);
    assertThat(fixture.state().at("/counts/stock.deduct").asInt()).isZero();
  }

  private void assertUncertainDeductionRecoversWithoutAnotherEffect(
      String delay, long immediateStock) throws Exception {
    fault("stock.deduct", Map.of(delay, 3000));
    UUID key = UUID.randomUUID();
    var response = create(key, body(), alice);
    assertStatus(response, 202);
    var failed = repository.findReservedKey("alice", key).orElseThrow();
    assertThat(failed.getStatus().name()).isEqualTo("RECOVERY_REQUIRED");
    assertInventory(immediateStock, 0, 0);
    assertStatus(create(key, body(), alice), 202);
    service.recover();
    fixture.awaitIdle();
    service.recover();
    assertInventory(997, 1, 1);
    assertThat(fixture.state().at("/counts/stock.deduct").asInt()).isEqualTo(2);
    assertThat(repository.findReservedKey("alice", key).orElseThrow().getStatus().name())
        .isEqualTo("CONFIRMED");
    assertThat(fixture.state().has("operations")).isFalse();
  }

  @Test
  void permanentDeductionConflictIsPreservedWithoutAutomaticRetry() throws Exception {
    fault("stock.deduct", Map.of("status", 409, "code", "STOCK_DEDUCTION_CONFLICT"));
    UUID id = id(create(UUID.randomUUID(), body(), alice));
    var blocked = repository.findById(id).orElseThrow();
    assertThat(blocked.getStatus().name()).isEqualTo("RECOVERY_REQUIRED");
    assertThat(blocked.isRecoveryBlocked()).isTrue();
    assertThat(blocked.getLastError()).isEqualTo("STOCK_DEDUCTION_CONFLICT");
    service.recover();
    service.recover();
    assertThat(fixture.state().at("/counts/stock.deduct").asInt()).isEqualTo(1);
    assertInventory(1000, 0, 0);
  }

  @Test
  void theNextRecoveryPassRetriesWithoutAPerOrderSchedule() throws Exception {
    fault("stock.deduct", Map.of("status", 503, "code", "SERVICE_UNAVAILABLE"));
    UUID id = id(create(UUID.randomUUID(), body(), alice));
    assertThat(fixture.state().at("/counts/stock.deduct").asInt()).isEqualTo(1);
    service.recover();
    assertThat(repository.findById(id).orElseThrow().getStatus().name()).isEqualTo("CONFIRMED");
    assertInventory(997, 1, 1);
  }

  @Test
  void deliveryRecoveryKeepsItsPhaseAfterAnotherLookupFailure() throws Exception {
    fault("delivery.get", Map.of("status", 503, "code", "UPSTREAM_UNAVAILABLE"));
    UUID id = id(create(UUID.randomUUID(), body(), alice));
    fault("delivery.get", Map.of("status", 503, "code", "UPSTREAM_UNAVAILABLE"));
    service.recover();
    var pending = repository.findById(id).orElseThrow();
    assertThat(pending.getRecoveryPhase().name()).isEqualTo("DELIVERY_CREATION");
    assertThat(pending.getLastError()).isEqualTo("UPSTREAM_UNAVAILABLE");
    assertThat(pending.isRecoveryBlocked()).isFalse();
    service.recover();
    assertThat(repository.findById(id).orElseThrow().getStatus().name()).isEqualTo("CONFIRMED");
    assertThat(fixture.state().at("/counts/stock.deduct").asInt()).isEqualTo(1);
    assertInventory(997, 1, 1);
  }

  @Test
  void restorationRecoveryDoesNotReturnToDeliveryAfterAnotherFailure() throws Exception {
    UUID id = id(create(UUID.randomUUID(), body(), alice));
    fault("stock.restore", Map.of("status", 503, "code", "UPSTREAM_UNAVAILABLE"));
    assertStatus(request("POST", "/api/orders/" + id + "/cancel", null, admin, null), 202);
    int lookups = fixture.state().at("/counts/delivery.get").asInt();
    fault("stock.restore", Map.of("status", 503, "code", "UPSTREAM_UNAVAILABLE"));
    service.recover();
    assertThat(repository.findById(id).orElseThrow().getRecoveryPhase().name())
        .isEqualTo("STOCK_RESTORATION");
    service.recover();
    assertThat(repository.findById(id).orElseThrow().getStatus().name()).isEqualTo("CANCELLED");
    assertThat(fixture.state().at("/counts/delivery.get").asInt()).isEqualTo(lookups);
    assertThat(fixture.state().at("/actors/stock.restore").asText()).isEqualTo("admin");
    assertInventory(1000, 1, 1);
  }

  @Test
  void exhaustedRetriesKeepTheUnknownOrderAndStopCallingHub() throws Exception {
    fault("stock.deduct", Map.of("status", 503, "code", "SERVICE_UNAVAILABLE", "remaining", 100));
    UUID id = id(create(UUID.randomUUID(), body(), alice));
    for (int i = 0; i < 25; i++) service.recover();
    var order = repository.findById(id).orElseThrow();
    assertThat(order.getStatus().name()).isEqualTo("RECOVERY_REQUIRED");
    assertThat(order.isRecoveryBlocked()).isTrue();
    assertThat(order.getRecoveryAttempts()).isEqualTo(20);
    assertThat(fixture.state().at("/counts/stock.deduct").asInt()).isEqualTo(20);
    assertInventory(1000, 0, 0);
  }

  @Test
  void creationCompensationRecoversLostResponseAndFinishesFailedRatherThanCancelled()
      throws Exception {
    fault("delivery.create", Map.of("status", 404, "code", "HUB_ROUTE_NOT_FOUND"));
    fault("stock.restore", Map.of("delay_after_ms", 3000));
    UUID key = UUID.randomUUID();
    var response = create(key, body(), alice);
    assertStatus(response, 202);
    UUID id = id(response);
    assertThat(repository.findById(id).orElseThrow().getRecoveryPhase().name())
        .isEqualTo("STOCK_COMPENSATION");
    fixture.awaitIdle();
    service.recover();
    var order = repository.findById(id).orElseThrow();
    assertThat(order.getStatus().name()).isEqualTo("FAILED");
    assertThat(order.getLastError()).isEqualTo("DELIVERY_CREATION_FAILED");
    assertInventory(1000, 0, 0);
    assertThat(fixture.state().path("restorations")).hasSize(1);
    assertThat(fixture.state().at("/counts/stock.restore").asInt()).isEqualTo(2);
    assertStatus(create(key, body(), alice), 200);
  }

  @Test
  void cancellationCompletesAfterDeliveryCancellationAndDeletionPreservesFirstAudit()
      throws Exception {
    UUID key = UUID.randomUUID();
    UUID id = id(create(key, body(), alice));
    assertStatus(request("POST", "/api/orders/" + id + "/cancel", null, alice, null), 200);
    assertStatus(request("POST", "/api/orders/" + id + "/cancel", null, alice, null), 200);
    assertInventory(1000, 1, 1);
    assertThat(fixture.state().at("/counts/stock.restore").asInt()).isEqualTo(1);
    assertThat(fixture.state().at("/deliveries/0/status").asText()).isEqualTo("CANCELLED");
    assertThat(fixture.state().at("/counts/stock.deduct").asInt()).isEqualTo(1);
    assertStatus(request("DELETE", "/api/orders/" + id, null, admin, null), 204);
    var first = repository.findIncludingDeleted(id).orElseThrow();
    assertStatus(request("DELETE", "/api/orders/" + id, null, admin, null), 204);
    var second = repository.findIncludingDeleted(id).orElseThrow();
    assertThat(second.getDeletedAt()).isEqualTo(first.getDeletedAt());
    assertThat(second.getDeletedBy()).isEqualTo("admin");
    assertThat(second.getCreatedBy()).isEqualTo("alice");
    assertStatus(request("GET", "/api/orders/" + id, null, alice, null), 404);
    assertThat(repository.count()).isZero();
    assertStatus(create(key, body(), alice), 409);
    assertThat(
            json(request("GET", "/api/orders", null, admin, null))
                .at("/data/total_elements")
                .asLong())
        .isZero();
  }

  @Test
  void restorationFailureKeepsCancellationPendingAndBlocksDeletionUntilRecovery() throws Exception {
    UUID id = id(create(UUID.randomUUID(), body(), alice));
    fault("stock.restore", Map.of("status", 503, "code", "UPSTREAM_UNAVAILABLE"));
    assertStatus(request("POST", "/api/orders/" + id + "/cancel", null, admin, null), 202);
    var pending = repository.findById(id).orElseThrow();
    assertThat(pending.getStatus().name()).isEqualTo("RECOVERY_REQUIRED");
    assertThat(pending.awaitingStockRestoration()).isTrue();
    assertThat(pending.getUpdatedBy()).isEqualTo("admin");
    assertThat(fixture.state().at("/deliveries/0/status").asText()).isEqualTo("CANCELLED");
    assertInventory(997, 1, 1);
    assertStatus(request("DELETE", "/api/orders/" + id, null, admin, null), 409);
    assertStatus(request("POST", "/api/orders/" + id + "/cancel", null, bob, null), 403);
    int lookups = fixture.state().at("/counts/delivery.get").asInt();
    service.recover();
    assertThat(repository.findById(id).orElseThrow().getStatus().name()).isEqualTo("CANCELLED");
    assertThat(repository.findById(id).orElseThrow().getUpdatedBy()).isEqualTo("admin");
    assertThat(fixture.state().at("/actors/stock.restore").asText()).isEqualTo("admin");
    assertThat(fixture.state().at("/counts/delivery.get").asInt()).isEqualTo(lookups);
    assertInventory(1000, 1, 1);
  }

  @Test
  void lostRestorationResponseAndConcurrentCancelsCannotIncreaseQuantityTwice() throws Exception {
    UUID id = id(create(UUID.randomUUID(), body(), alice));
    fault("stock.restore", Map.of("delay_after_ms", 3000));
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(
              () -> request("POST", "/api/orders/" + id + "/cancel", null, admin, null));
      await()
          .atMost(Duration.ofSeconds(3))
          .untilAsserted(() -> assertThat(fixture.state().path("restorations")).hasSize(1));
      assertThat(repository.findById(id).orElseThrow().getStatus().name()).isEqualTo("CANCELLING");
      assertStatus(request("DELETE", "/api/orders/" + id, null, admin, null), 409);
      assertStatus(request("POST", "/api/orders/" + id + "/cancel", null, alice, null), 202);
      assertStatus(first.get(5, TimeUnit.SECONDS), 202);
    }
    assertThat(repository.findById(id).orElseThrow().awaitingStockRestoration()).isTrue();
    fixture.awaitIdle();
    service.recover();
    assertStatus(request("POST", "/api/orders/" + id + "/cancel", null, alice, null), 200);
    assertInventory(1000, 1, 1);
    assertThat(fixture.state().path("restorations")).hasSize(1);
    assertThat(fixture.state().at("/counts/stock.restore").asInt()).isEqualTo(2);
    assertThat(fixture.state().at("/counts/delivery.cancel").asInt()).isEqualTo(1);
    assertThat(fixture.state().at("/actors/stock.restore").asText()).isEqualTo("admin");
  }

  @Test
  void malformedRestorationAcknowledgementCannotCompleteCancellation() throws Exception {
    UUID id = id(create(UUID.randomUUID(), body(), alice));
    fault(
        "stock.restore",
        Map.of("response", Map.of("data", Map.of("id", UUID.randomUUID(), "quantity", 1000))));
    assertStatus(request("POST", "/api/orders/" + id + "/cancel", null, alice, null), 202);
    assertThat(repository.findById(id).orElseThrow().getStatus().name())
        .isEqualTo("RECOVERY_REQUIRED");
    assertStatus(request("DELETE", "/api/orders/" + id, null, admin, null), 409);
    service.recover();
    assertThat(repository.findById(id).orElseThrow().getStatus().name()).isEqualTo("CANCELLED");
    assertInventory(1000, 1, 1);
    assertThat(fixture.state().path("restorations")).hasSize(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"HUB_MOVING", "DESTINATION_HUB_ARRIVED", "COMPANY_MOVING"})
  void cancellationAfterDepartureIsRejectedAndDeletionDoesNotRestoreStock(String status)
      throws Exception {
    UUID id = id(create(UUID.randomUUID(), body(), alice));
    fixture.control(
        Map.of("action", "delivery_status", "order_id", id.toString(), "status", status));
    assertStatus(request("POST", "/api/orders/" + id + "/cancel", null, alice, null), 409);
    assertThat(repository.findById(id).orElseThrow().getStatus().name()).isEqualTo("CONFIRMED");
    assertStatus(request("DELETE", "/api/orders/" + id, null, admin, null), 409);
    fixture.control(
        Map.of("action", "delivery_status", "order_id", id.toString(), "status", "DELIVERED"));
    assertStatus(request("DELETE", "/api/orders/" + id, null, admin, null), 204);
    assertThat(fixture.state().at("/stocks/0/quantity").asLong()).isEqualTo(997);
    assertThat(fixture.state().at("/counts/stock.restore").asInt()).isZero();
  }

  @Test
  void deliveredOrderCannotBeCancelledAndCanBeDeleted() throws Exception {
    UUID id = id(create(UUID.randomUUID(), body(), alice));
    fixture.control(
        Map.of("action", "delivery_status", "order_id", id.toString(), "status", "DELIVERED"));

    assertStatus(request("POST", "/api/orders/" + id + "/cancel", null, alice, null), 409);
    assertThat(repository.findById(id).orElseThrow().getStatus().name()).isEqualTo("CONFIRMED");
    assertStatus(request("DELETE", "/api/orders/" + id, null, admin, null), 204);
    assertThat(fixture.state().at("/stocks/0/quantity").asLong()).isEqualTo(997);
  }

  @Test
  void departureWinningConcurrentCancellationNeverRestoresStock() throws Exception {
    UUID id = id(create(UUID.randomUUID(), body(), alice));
    String deliveryId = fixture.state().at("/deliveries/0/delivery_id").asText();
    fault("delivery.cancel", Map.of("delay_before_ms", 3000));
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var cancellation =
          executor.submit(
              () -> request("POST", "/api/orders/" + id + "/cancel", null, alice, null));
      await()
          .atMost(Duration.ofSeconds(3))
          .untilAsserted(
              () -> assertThat(fixture.state().at("/counts/delivery.cancel").asInt()).isEqualTo(1));
      assertThat(fixture.state().path("inflight").asInt()).isPositive();
      assertThat(fixture.depart(UUID.fromString(deliveryId))).isEqualTo(200);
      assertStatus(cancellation.get(5, TimeUnit.SECONDS), 202);
    }
    fixture.awaitIdle();
    service.recover();
    assertStatus(request("POST", "/api/orders/" + id + "/cancel", null, alice, null), 409);
    assertThat(repository.findById(id).orElseThrow().getStatus().name()).isEqualTo("CONFIRMED");
    assertThat(fixture.state().at("/deliveries/0/status").asText()).isEqualTo("HUB_MOVING");
    assertThat(fixture.state().at("/stocks/0/quantity").asLong()).isEqualTo(997);
    assertThat(fixture.state().at("/counts/stock.restore").asInt()).isZero();
  }

  @Test
  void cancellationWinningConcurrentDepartureRestoresStock() throws Exception {
    UUID id = id(create(UUID.randomUUID(), body(), alice));
    String deliveryId = fixture.state().at("/deliveries/0/delivery_id").asText();
    fault("delivery.cancel", Map.of("delay_after_ms", 3000));
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var cancellation =
          executor.submit(
              () -> request("POST", "/api/orders/" + id + "/cancel", null, alice, null));
      await()
          .atMost(Duration.ofSeconds(3))
          .untilAsserted(
              () ->
                  assertThat(fixture.state().at("/deliveries/0/status").asText())
                      .isEqualTo("CANCELLED"));
      assertThat(fixture.state().path("inflight").asInt()).isPositive();
      assertThat(fixture.depart(UUID.fromString(deliveryId))).isEqualTo(409);
      assertStatus(cancellation.get(5, TimeUnit.SECONDS), 202);
    }
    fixture.awaitIdle();
    service.recover();
    assertStatus(request("POST", "/api/orders/" + id + "/cancel", null, alice, null), 200);
    assertThat(repository.findById(id).orElseThrow().getStatus().name()).isEqualTo("CANCELLED");
    assertThat(fixture.state().at("/stocks/0/quantity").asLong()).isEqualTo(1000);
    assertThat(fixture.state().at("/counts/stock.deduct").asInt()).isEqualTo(1);
  }

  @Test
  void cancellationTimeoutRemainsPendingUntilAcknowledgedCancellation() throws Exception {
    UUID id = id(create(UUID.randomUUID(), body(), alice));
    fault("delivery.cancel", Map.of("delay_before_ms", 3000));
    assertStatus(request("POST", "/api/orders/" + id + "/cancel", null, alice, null), 202);
    assertThat(fixture.state().at("/stocks/0/quantity").asLong()).isEqualTo(997);
    assertThat(fixture.state().at("/deliveries/0/status").asText()).isEqualTo("HUB_WAITING");
    service.recover();
    fixture.awaitIdle();
    assertThat(repository.findById(id).orElseThrow().getStatus().name()).isEqualTo("CANCELLED");
    assertInventory(1000, 1, 1);
    assertThat(fixture.state().at("/counts/stock.restore").asInt()).isEqualTo(1);
  }

  @Test
  void deletionCanResumeAfterRemoteDeletionCommittedButAcknowledgementTimedOut() throws Exception {
    UUID id = id(create(UUID.randomUUID(), body(), alice));
    assertStatus(request("POST", "/api/orders/" + id + "/cancel", null, alice, null), 200);
    fault("delivery.delete", Map.of("delay_after_ms", 3000));
    assertStatus(request("DELETE", "/api/orders/" + id, null, admin, null), 503);
    assertThat(repository.findById(id)).isPresent();
    assertStatus(request("DELETE", "/api/orders/" + id, null, admin, null), 204);
    assertThat(repository.findById(id)).isEmpty();
  }

  @Test
  void searchAppliesTheSameOwnerFiltersToContentAndCountsAndValidatesPaging() throws Exception {
    create(UUID.randomUUID(), body(), alice);
    create(UUID.randomUUID(), body(), alice);
    create(UUID.randomUUID(), body(), bob);
    UUID deletedId = id(create(UUID.randomUUID(), body(), alice));
    jdbc.update(
        "UPDATE p_orders SET deleted_at=current_timestamp, deleted_by='admin' WHERE id=?",
        deletedId);
    var page = json(request("GET", "/api/orders?size=1", null, alice, null)).path("data");
    assertThat(page.path("total_elements").asInt()).isEqualTo(2);
    assertThat(page.path("total_pages").asInt()).isEqualTo(2);
    assertThat(page.path("content")).hasSize(1);
    var empty = json(request("GET", "/api/orders?page=5&size=1", null, alice, null)).path("data");
    assertThat(empty.path("content")).isEmpty();
    assertThat(empty.path("total_elements").asInt()).isEqualTo(2);
    assertThat(
            json(request("GET", "/api/orders", null, admin, null))
                .at("/data/total_elements")
                .asInt())
        .isEqualTo(3);
    assertThat(
            json(request("GET", "/api/orders?product_id=" + UUID.randomUUID(), null, alice, null))
                .at("/data/total_elements")
                .asInt())
        .isZero();
    var beyond =
        json(request("GET", "/api/orders?page=2147483647&size=20", null, alice, null)).path("data");
    assertThat(beyond.path("content")).isEmpty();
    assertThat(beyond.path("total_elements").asLong()).isEqualTo(2);
    for (String query :
        List.of(
            "page=-1",
            "page=2147483647&sort=created_by,desc",
            "size=0",
            "size=101",
            "sort=created_by,desc",
            "sort=created_at,invalid",
            "status=invalid",
            "created_from=invalid",
            "created_from=2026-10-16T00:00:00Z&created_to=2026-10-15T00:00:00Z"))
      assertStatus(request("GET", "/api/orders?" + query, null, alice, null), 400);
  }

  @Test
  void documentsActualPublicContractsAndSupportsBigintQuantityAndTextNotes() throws Exception {
    fixture.control(Map.of("action", "stock", "quantity", 5000000000L));
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
                "select data_type from information_schema.columns where table_name='p_orders' and column_name='unit_price'",
                String.class))
        .isEqualTo("bigint");
  }

  @Test
  void validatesReferenceExistenceSupplierOwnershipAndRejectsCancelBeforeConfirmation()
      throws Exception {
    var invalid = body();
    invalid.put("supplier_company_id", UUID.randomUUID().toString());
    assertStatus(create(UUID.randomUUID(), invalid, alice), 404);
    invalid = body();
    invalid.put("product_id", UUID.randomUUID().toString());
    var missingProduct = create(UUID.randomUUID(), invalid, alice);
    assertStatus(missingProduct, 404);
    assertThat(json(missingProduct).path("code").asText()).isEqualTo("PRODUCT_NOT_FOUND");
    invalid = body();
    invalid.put("supplier_company_id", OrderClientFixture.RECEIVER);
    assertStatus(create(UUID.randomUUID(), invalid, alice), 409);
    assertThat(repository.count()).isZero();
    fault("delivery.create", Map.of("delay_after_ms", 3000));
    UUID id = id(create(UUID.randomUUID(), body(), alice));
    assertStatus(request("POST", "/api/orders/" + id + "/cancel", null, alice, null), 409);
    assertStatus(request("DELETE", "/api/orders/" + id, null, admin, null), 409);
    assertThat(fixture.state().at("/counts/stock.restore").asInt()).isZero();
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
    assertThat(fixture.state().at("/counts/slack").asInt()).isEqualTo(1);
    assertThat(fixture.state().at("/stocks/0/quantity").asLong()).isEqualTo(997);
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

  @Test
  void overlappingCancellationBeforeTransitionReturnsAccepted() throws Exception {
    UUID id = id(create(UUID.randomUUID(), body(), alice));
    var owned = new CountDownLatch(1);
    var proceed = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              Object running = invocation.callRealMethod();
              if (running == null) {
                owned.countDown();
                assertThat(proceed.await(5, TimeUnit.SECONDS)).isTrue();
              }
              return running;
            })
        .when(active)
        .begin(eq(id), eq(Operation.CANCEL));
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(
              () -> request("POST", "/api/orders/" + id + "/cancel", null, alice, null));
      try {
        assertThat(owned.await(5, TimeUnit.SECONDS)).isTrue();
        assertStatus(request("POST", "/api/orders/" + id + "/cancel", null, alice, null), 202);
      } finally {
        proceed.countDown();
      }
      assertStatus(first.get(10, TimeUnit.SECONDS), 200);
    }
    assertInventory(1000, 1, 1);
    assertThat(fixture.state().at("/counts/stock.restore").asInt()).isEqualTo(1);
    assertThat(fixture.state().at("/counts/stock.deduct").asInt()).isEqualTo(1);
  }

  @Test
  void overlappingCancellationAfterTerminalCommitReturnsSuccess() throws Exception {
    UUID id = id(create(UUID.randomUUID(), body(), alice));
    var owned = new CountDownLatch(1);
    var staleRead = new CountDownLatch(1);
    var proceed = new CountDownLatch(1);
    var terminal = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var attempts = new AtomicInteger();
    doAnswer(
            invocation -> {
              if (attempts.incrementAndGet() == 1) {
                Object running = invocation.callRealMethod();
                owned.countDown();
                assertThat(proceed.await(5, TimeUnit.SECONDS)).isTrue();
                return running;
              }
              staleRead.countDown();
              assertThat(terminal.await(5, TimeUnit.SECONDS)).isTrue();
              return invocation.callRealMethod();
            })
        .when(active)
        .begin(eq(id), eq(Operation.CANCEL));
    doAnswer(
            invocation -> {
              terminal.countDown();
              assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
              return invocation.callRealMethod();
            })
        .when(active)
        .end(id);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(
              () -> request("POST", "/api/orders/" + id + "/cancel", null, alice, null));
      try {
        assertThat(owned.await(5, TimeUnit.SECONDS)).isTrue();
        var second =
            executor.submit(
                () -> request("POST", "/api/orders/" + id + "/cancel", null, alice, null));
        assertThat(staleRead.await(5, TimeUnit.SECONDS)).isTrue();
        proceed.countDown();
        assertStatus(second.get(10, TimeUnit.SECONDS), 200);
      } finally {
        proceed.countDown();
        release.countDown();
      }
      assertStatus(first.get(10, TimeUnit.SECONDS), 200);
    }
    assertInventory(1000, 1, 1);
    assertThat(fixture.state().at("/counts/stock.restore").asInt()).isEqualTo(1);
    assertThat(fixture.state().at("/counts/stock.deduct").asInt()).isEqualTo(1);
    assertThat(fixture.state().at("/counts/delivery.cancel").asInt()).isEqualTo(1);
  }

  @Test
  void cancellationAfterRecoveryCompletedAndReleasedReturnsSuccess() throws Exception {
    assertCancellationAfterRecoveryReleased(false);
  }

  @Test
  void cancellationAfterDepartureRecoveryReleasedReturnsConflict() throws Exception {
    assertCancellationAfterRecoveryReleased(true);
  }

  @Test
  void cancellationDuringStaleRecoveryClaimIsPersistedAndCompleted() throws Exception {
    assertCancellationWhileRecoveryOwnsConfirmedOrder(true);
  }

  @Test
  void cancellationBeforeCreationRecoveryReleasesIsPersistedAndCompleted() throws Exception {
    assertCancellationWhileRecoveryOwnsConfirmedOrder(false);
  }

  @Test
  void cancellationRespectsReplacementOwnerAfterRecoveryReleases() throws Exception {
    assertCancellationAfterRecoveryOwnerChanges(true);
  }

  @Test
  void cancellationClaimsIdleOrderAfterRecoveryReleases() throws Exception {
    assertCancellationAfterRecoveryOwnerChanges(false);
  }

  @Test
  void staleCreationCandidatePreservesCurrentCancellationActor() throws Exception {
    fault("delivery.create", Map.of("delay_after_ms", 3000));
    var created = create(UUID.randomUUID(), body(), alice);
    assertStatus(created, 202);
    UUID id = id(created);
    var stale = repository.findById(id).orElseThrow();
    fixture.awaitIdle();
    service.recover();
    assertThat(repository.findById(id).orElseThrow().getStatus().name()).isEqualTo("CONFIRMED");
    fault("delivery.cancel", Map.of("delay_after_ms", 3000));
    assertStatus(request("POST", "/api/orders/" + id + "/cancel", null, admin, null), 202);
    fixture.awaitIdle();
    doReturn(List.of(stale))
        .when(repository)
        .findRecoveryCandidates(anyCollection(), org.mockito.ArgumentMatchers.any());
    service.recover();
    var cancelled = repository.findById(id).orElseThrow();
    assertThat(cancelled.getStatus().name()).isEqualTo("CANCELLED");
    assertThat(cancelled.getUpdatedBy()).isEqualTo("admin");
    assertThat(fixture.state().at("/actors/delivery.cancel").asText()).isEqualTo("admin");
    assertThat(fixture.state().at("/counts/stock.deduct").asInt()).isEqualTo(1);
    assertInventory(1000, 1, 1);
    assertThat(fixture.state().at("/actors/stock.restore").asText()).isEqualTo("admin");
  }

  @Test
  void overlappingDeletionAfterFirstCompletionReturnsNoContent() throws Exception {
    UUID id = id(create(UUID.randomUUID(), body(), alice));
    assertStatus(request("POST", "/api/orders/" + id + "/cancel", null, alice, null), 200);
    var paused = new CountDownLatch(1);
    var proceed = new CountDownLatch(1);
    var attempts = new AtomicInteger();
    doAnswer(
            invocation -> {
              if (attempts.incrementAndGet() == 1) {
                paused.countDown();
                assertThat(proceed.await(5, TimeUnit.SECONDS)).isTrue();
              }
              return invocation.callRealMethod();
            })
        .when(active)
        .begin(eq(id), eq(Operation.DELETE));
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = executor.submit(() -> request("DELETE", "/api/orders/" + id, null, admin, null));
      try {
        assertThat(paused.await(5, TimeUnit.SECONDS)).isTrue();
        assertStatus(request("DELETE", "/api/orders/" + id, null, admin, null), 204);
      } finally {
        proceed.countDown();
      }
      assertStatus(first.get(10, TimeUnit.SECONDS), 204);
    }
    assertThat(repository.findIncludingDeleted(id).orElseThrow().getDeletedBy()).isEqualTo("admin");
    assertThat(fixture.state().at("/counts/delivery.delete").asInt()).isEqualTo(1);
  }

  @Test
  void deletionOfFailedOrderWithoutDeliverySkipsRemoteDeleteAndMissingOrderReturnsNotFound()
      throws Exception {
    assertStatus(request("DELETE", "/api/orders/" + UUID.randomUUID(), null, admin, null), 404);
    fixture.control(Map.of("action", "stock", "quantity", 0));
    UUID key = UUID.randomUUID();
    assertStatus(create(key, body(), alice), 409);
    var failed = repository.findReservedKey("alice", key).orElseThrow();
    assertThat(failed.getStatus().name()).isEqualTo("FAILED");
    assertStatus(request("DELETE", "/api/orders/" + failed.getId(), null, admin, null), 204);
    assertThat(repository.findById(failed.getId())).isEmpty();
    assertThat(fixture.state().at("/counts/delivery.delete").asInt()).isZero();
    assertThat(fixture.state().at("/counts/stock.restore").asInt()).isZero();
  }

  private void assertCancellationAfterRecoveryOwnerChanges(boolean replacementOwner)
      throws Exception {
    UUID id = id(create(UUID.randomUUID(), body(), alice));
    assertThat(active.begin(id, Operation.RECOVER)).isNull();
    doAnswer(
            invocation -> {
              var running = (Operation) invocation.callRealMethod();
              if (running == Operation.RECOVER) {
                active.end(id);
                if (replacementOwner) assertThat(active.begin(id, Operation.DELETE)).isNull();
              }
              return running;
            })
        .when(active)
        .begin(eq(id), eq(Operation.CANCEL));
    try {
      assertStatus(
          request("POST", "/api/orders/" + id + "/cancel", null, admin, null),
          replacementOwner ? 409 : 200);
      var order = repository.findById(id).orElseThrow();
      assertThat(order.getStatus().name()).isEqualTo(replacementOwner ? "CONFIRMED" : "CANCELLED");
      assertThat(order.getRecoveryPhase()).isNull();
      assertThat(order.getUpdatedBy()).isEqualTo(replacementOwner ? "alice" : "admin");
      assertThat(fixture.state().at("/counts/delivery.cancel").asInt())
          .isEqualTo(replacementOwner ? 0 : 1);
      assertThat(fixture.state().at("/counts/stock.deduct").asInt()).isEqualTo(1);
      assertInventory(replacementOwner ? 997 : 1000, 1, 1);
    } finally {
      if (replacementOwner) active.end(id);
    }
  }

  private void assertCancellationWhileRecoveryOwnsConfirmedOrder(boolean staleCandidate)
      throws Exception {
    fault("delivery.create", Map.of("status", 503, "code", "UPSTREAM_UNAVAILABLE"));
    var created = create(UUID.randomUUID(), body(), alice);
    assertStatus(created, 202);
    UUID id = id(created);
    var candidate = repository.findById(id).orElseThrow();
    if (staleCandidate) {
      service.recover();
      doReturn(List.of(candidate))
          .when(repository)
          .findRecoveryCandidates(anyCollection(), org.mockito.ArgumentMatchers.any());
    }
    var paused = new CountDownLatch(1);
    var proceed = new CountDownLatch(1);
    if (staleCandidate) {
      doAnswer(
              invocation -> {
                Object running = invocation.callRealMethod();
                if (running == null) {
                  paused.countDown();
                  assertThat(proceed.await(5, TimeUnit.SECONDS)).isTrue();
                }
                return running;
              })
          .when(active)
          .begin(eq(id), eq(Operation.RECOVER));
    } else {
      doAnswer(
              invocation -> {
                paused.countDown();
                assertThat(proceed.await(5, TimeUnit.SECONDS)).isTrue();
                return invocation.callRealMethod();
              })
          .when(active)
          .end(id);
    }
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var recovery = executor.submit(service::recover);
      try {
        assertThat(paused.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(repository.findById(id).orElseThrow().getStatus().name()).isEqualTo("CONFIRMED");
        var accepted = request("POST", "/api/orders/" + id + "/cancel", null, admin, null);
        assertStatus(accepted, 202);
        assertThat(json(accepted).at("/data/status").asText()).isEqualTo("CANCELLING");
        assertStatus(request("POST", "/api/orders/" + id + "/cancel", null, alice, null), 202);
        var pending = repository.findById(id).orElseThrow();
        assertThat(pending.getRecoveryPhase().name()).isEqualTo("DELIVERY_CANCELLATION");
        assertThat(pending.getUpdatedBy()).isEqualTo("admin");
        assertThat(fixture.state().at("/counts/delivery.cancel").asInt()).isZero();
        assertThat(fixture.state().at("/counts/stock.restore").asInt()).isZero();
      } finally {
        proceed.countDown();
      }
      recovery.get(10, TimeUnit.SECONDS);
    }
    service.recover();
    var cancelled = repository.findById(id).orElseThrow();
    assertThat(cancelled.getStatus().name()).isEqualTo("CANCELLED");
    assertThat(cancelled.getRecoveryPhase()).isNull();
    assertThat(cancelled.getUpdatedBy()).isEqualTo("admin");
    assertThat(fixture.state().at("/actors/delivery.cancel").asText()).isEqualTo("admin");
    assertThat(fixture.state().at("/counts/delivery.cancel").asInt()).isEqualTo(1);
    assertThat(fixture.state().at("/counts/stock.deduct").asInt()).isEqualTo(1);
    assertInventory(1000, 1, 1);
    assertThat(fixture.state().at("/counts/stock.restore").asInt()).isEqualTo(1);
  }

  private void assertCancellationAfterRecoveryReleased(boolean departed) throws Exception {
    UUID id = id(create(UUID.randomUUID(), body(), alice));
    fault("delivery.cancel", Map.of("status", 503, "code", "UPSTREAM_UNAVAILABLE"));
    assertStatus(request("POST", "/api/orders/" + id + "/cancel", null, alice, null), 202);
    assertThat(repository.findById(id).orElseThrow().getStatus().name())
        .isEqualTo("RECOVERY_REQUIRED");
    if (departed)
      fixture.control(
          Map.of("action", "delivery_status", "order_id", id.toString(), "status", "HUB_MOVING"));
    var staleRead = new CountDownLatch(1);
    var proceed = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              staleRead.countDown();
              assertThat(proceed.await(5, TimeUnit.SECONDS)).isTrue();
              return invocation.callRealMethod();
            })
        .when(active)
        .begin(eq(id), eq(Operation.CANCEL));
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var cancellation =
          executor.submit(
              () -> request("POST", "/api/orders/" + id + "/cancel", null, alice, null));
      try {
        assertThat(staleRead.await(5, TimeUnit.SECONDS)).isTrue();
        service.recover();
        assertThat(repository.findById(id).orElseThrow().getStatus().name())
            .isEqualTo(departed ? "CONFIRMED" : "CANCELLED");
        int deliveryLookups = fixture.state().at("/counts/delivery.get").asInt();
        proceed.countDown();
        assertStatus(cancellation.get(10, TimeUnit.SECONDS), departed ? 409 : 200);
        assertThat(fixture.state().at("/counts/delivery.get").asInt()).isEqualTo(deliveryLookups);
      } finally {
        proceed.countDown();
      }
    }
    assertInventory(departed ? 997 : 1000, 1, 1);
    assertThat(fixture.state().at("/counts/stock.deduct").asInt()).isEqualTo(1);
    assertThat(fixture.state().at("/counts/delivery.cancel").asInt()).isEqualTo(departed ? 1 : 2);
  }

  @Test
  void manualRecoveryRequiresMasterFreshSnapshotAndExplicitUpstreamCheck() throws Exception {
    fault("stock.deduct", Map.of("status", 401, "code", "UNAUTHORIZED"));
    final var id = id(create(UUID.randomUUID(), body(), alice));
    final var path = "/api/orders/" + id + "/recovery";
    final var command = recoveryCommand(id);
    assertStatus(request("GET", path, null, null, null), 401);
    assertStatus(request("POST", path, command, null, null), 401);
    assertStatus(request("GET", path, null, alice, null), 403);
    assertStatus(request("POST", path, command, alice, null), 403);
    command.put("upstream_checked", false);
    assertStatus(request("POST", path, command, admin, null), 400);
    command.remove("upstream_checked");
    assertStatus(request("POST", path, command, admin, null), 400);
    command.put("upstream_checked", true);
    command.put("expected_attempts", 1);
    assertStatus(request("POST", path, command, admin, null), 409);
    assertThat(fixture.state().at("/counts/stock.deduct").asInt()).isOne();
    assertThat(repository.findById(id).orElseThrow().isRecoveryBlocked()).isTrue();
    assertStatus(
        request("GET", "/api/orders/" + UUID.randomUUID() + "/recovery", null, admin, null), 404);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "STOCK_DEDUCTION",
        "DELIVERY_CREATION",
        "DELIVERY_CANCELLATION",
        "STOCK_RESTORATION",
        "STOCK_COMPENSATION"
      })
  void manualRecoveryUsesEveryStoredPhaseAndOriginalActor(String phase) throws Exception {
    final UUID id;
    if (phase.equals("STOCK_DEDUCTION")) {
      fault("stock.deduct", Map.of("status", 401, "code", "UNAUTHORIZED"));
      id = id(create(UUID.randomUUID(), body(), alice));
    } else if (phase.equals("DELIVERY_CREATION")) {
      fault("delivery.get", Map.of("status", 403, "code", "FORBIDDEN"));
      id = id(create(UUID.randomUUID(), body(), alice));
    } else if (phase.equals("STOCK_COMPENSATION")) {
      fault("delivery.create", Map.of("status", 404, "code", "HUB_ROUTE_NOT_FOUND"));
      fault("stock.restore", Map.of("status", 401, "code", "UNAUTHORIZED"));
      id = id(create(UUID.randomUUID(), body(), alice));
    } else {
      id = id(create(UUID.randomUUID(), body(), alice));
      fault(
          phase.equals("STOCK_RESTORATION") ? "stock.restore" : "delivery.get",
          Map.of("status", 401, "code", "UNAUTHORIZED"));
      assertStatus(request("POST", "/api/orders/" + id + "/cancel", null, alice, null), 202);
    }
    final var before = repository.findById(id).orElseThrow();
    assertThat(before.getRecoveryPhase().name()).isEqualTo(phase);
    assertThat(before.isRecoveryBlocked()).isTrue();
    final var command = recoveryCommand(id);
    final var resumed = request("POST", "/api/orders/" + id + "/recovery", command, admin, null);
    assertStatus(resumed, 200);
    final boolean creation = phase.equals("STOCK_DEDUCTION") || phase.equals("DELIVERY_CREATION");
    assertThat(json(resumed).at("/data/status").asText())
        .isEqualTo(
            creation ? "CONFIRMED" : phase.equals("STOCK_COMPENSATION") ? "FAILED" : "CANCELLED");
    final var saved = repository.findById(id).orElseThrow();
    assertThat(saved.getCreatedBy()).isEqualTo("alice");
    assertThat(saved.getUpdatedBy()).isEqualTo("alice");
    assertThat(saved.getRecoveryPhase()).isNull();
    assertInventory(
        creation ? 997 : 1000,
        phase.equals("STOCK_COMPENSATION") ? 0 : 1,
        phase.equals("STOCK_COMPENSATION") ? 0 : 1);
    final var counts = fixture.state().path("counts").deepCopy();
    assertStatus(request("POST", "/api/orders/" + id + "/recovery", command, admin, null), 409);
    assertThat(fixture.state().path("counts")).isEqualTo(counts);
    assertThat(
            fixture.state().at("/actors/" + (creation ? "stock.deduct" : "stock.restore")).asText())
        .isEqualTo("alice");
  }

  @Test
  void failedManualRecoveryKeepsTheBlockBudgetAndRejectsAnOldRequest() throws Exception {
    fault("stock.deduct", Map.of("status", 401, "code", "UNAUTHORIZED"));
    final var id = id(create(UUID.randomUUID(), body(), alice));
    final var path = "/api/orders/" + id + "/recovery";
    final var command = recoveryCommand(id);
    fault("stock.deduct", Map.of("status", 503, "code", "SERVICE_UNAVAILABLE"));
    assertStatus(request("POST", path, command, admin, null), 202);
    final var after = repository.findById(id).orElseThrow();
    assertThat(after.isRecoveryBlocked()).isTrue();
    assertThat(after.getRecoveryAttempts()).isOne();
    assertThat(after.getStatus().name()).isEqualTo("RECOVERY_REQUIRED");
    assertThat(after.getRecoveryPhase().name()).isEqualTo("STOCK_DEDUCTION");
    service.recover();
    assertStatus(request("POST", path, command, admin, null), 409);
    assertThat(fixture.state().at("/counts/stock.deduct").asInt()).isEqualTo(2);
    fault("stock.deduct", Map.of("status", 409, "code", "STOCK_DEDUCTION_CONFLICT"));
    assertStatus(request("POST", path, recoveryCommand(id), admin, null), 202);
    final var blocked = repository.findById(id).orElseThrow();
    assertThat(blocked.getStatus().name()).isEqualTo("RECOVERY_REQUIRED");
    assertThat(blocked.getLastError()).isEqualTo("STOCK_DEDUCTION_CONFLICT");
    assertThat(blocked.getRecoveryAttempts()).isOne();
    assertThat(blocked.isRecoveryBlocked()).isTrue();
    assertStatus(request("POST", path, recoveryCommand(id), admin, null), 200);
    assertInventory(997, 1, 1);
  }

  @Test
  void simultaneousManualRecoveryHasOneOwnerAndDoesNotRepeatEffects() throws Exception {
    fault("stock.deduct", Map.of("status", 401, "code", "UNAUTHORIZED"));
    final var id = id(create(UUID.randomUUID(), body(), alice));
    final var command = recoveryCommand(id);
    final var path = "/api/orders/" + id + "/recovery";
    final var paused = new CountDownLatch(1);
    final var proceed = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              final var running = invocation.callRealMethod();
              if (running == null) {
                paused.countDown();
                assertThat(proceed.await(5, TimeUnit.SECONDS)).isTrue();
              }
              return running;
            })
        .when(active)
        .begin(eq(id), eq(Operation.RECOVER));
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final var first = executor.submit(() -> request("POST", path, command, admin, null));
      try {
        assertThat(paused.await(5, TimeUnit.SECONDS)).isTrue();
        assertStatus(request("POST", path, command, admin, null), 409);
        service.recover();
        assertThat(fixture.state().at("/counts/stock.deduct").asInt()).isOne();
      } finally {
        proceed.countDown();
      }
      assertStatus(first.get(10, TimeUnit.SECONDS), 200);
    }
    assertThat(fixture.state().at("/counts/stock.deduct").asInt()).isEqualTo(2);
    assertInventory(997, 1, 1);
  }

  @Test
  void manualRestorationCanRetryAfterRemoteCommitAndLocalSaveFailure() throws Exception {
    final var id = id(create(UUID.randomUUID(), body(), alice));
    fault("stock.restore", Map.of("status", 401, "code", "UNAUTHORIZED"));
    assertStatus(request("POST", "/api/orders/" + id + "/cancel", null, alice, null), 202);
    final var command = recoveryCommand(id);
    jdbc.execute(
        """
        CREATE FUNCTION reject_manual_completion() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN RAISE EXCEPTION 'test final save failure'; END $$;
        CREATE TRIGGER reject_manual_completion BEFORE UPDATE ON p_orders
        FOR EACH ROW WHEN (NEW.status = 'CANCELLED') EXECUTE FUNCTION reject_manual_completion()
        """);
    try {
      assertStatus(request("POST", "/api/orders/" + id + "/recovery", command, admin, null), 500);
    } finally {
      jdbc.execute(
          "DROP TRIGGER reject_manual_completion ON p_orders; DROP FUNCTION reject_manual_completion()");
    }
    final var pending = repository.findById(id).orElseThrow();
    assertThat(pending.getStatus().name()).isEqualTo("CANCELLING");
    assertThat(pending.isRecoveryBlocked()).isTrue();
    assertThat(pending.getRecoveryAttempts()).isZero();
    assertThat(pending.getRecoveryPhase().name()).isEqualTo("STOCK_RESTORATION");
    assertThat(pending.getUpdatedAt().toString()).isNotEqualTo(command.get("expected_updated_at"));
    service.recover();
    assertStatus(request("POST", "/api/orders/" + id + "/recovery", command, admin, null), 409);
    assertStatus(
        request("POST", "/api/orders/" + id + "/recovery", recoveryCommand(id), admin, null), 200);
    assertThat(fixture.state().path("restorations")).hasSize(1);
    assertInventory(1000, 1, 1);
  }

  @Test
  void manualRecoveryRefusesInconsistentPhasesAndUnblockedOrDeletedOrders() throws Exception {
    fault("stock.deduct", Map.of("status", 401, "code", "UNAUTHORIZED"));
    final var id = id(create(UUID.randomUUID(), body(), alice));
    jdbc.update("UPDATE p_orders SET recovery_phase=null WHERE id=?", id);
    final var command = new HashMap<String, Object>();
    command.put(
        "expected_updated_at", repository.findById(id).orElseThrow().getUpdatedAt().toString());
    command.put("expected_phase", "STOCK_DEDUCTION");
    command.put("expected_attempts", 0);
    command.put("upstream_checked", true);
    assertStatus(request("POST", "/api/orders/" + id + "/recovery", command, admin, null), 409);
    final var confirmed = id(create(UUID.randomUUID(), body(), alice));
    assertStatus(
        request("POST", "/api/orders/" + confirmed + "/recovery", command, admin, null), 409);
    jdbc.update("UPDATE p_orders SET deleted_at=now(), deleted_by='admin' WHERE id=?", id);
    assertStatus(request("POST", "/api/orders/" + id + "/recovery", command, admin, null), 404);
    assertThat(fixture.state().at("/counts/stock.deduct").asInt()).isEqualTo(2);
  }

  private Map<String, Object> recoveryCommand(UUID id) throws Exception {
    final var response = request("GET", "/api/orders/" + id + "/recovery", null, admin, null);
    assertStatus(response, 200);
    final var snapshot = json(response).path("data");
    return new HashMap<>(
        Map.of(
            "expected_updated_at",
            snapshot.path("updated_at").asText(),
            "expected_phase",
            snapshot.path("recovery_phase").asText(),
            "expected_attempts",
            snapshot.path("recovery_attempts").asInt(),
            "upstream_checked",
            true));
  }

  private Map<String, Object> body() {
    return new HashMap<>(
        Map.of(
            "receiver_company_id",
            OrderClientFixture.RECEIVER,
            "supplier_company_id",
            OrderClientFixture.SUPPLIER,
            "product_id",
            OrderClientFixture.PRODUCT,
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
    var state = fixture.state();
    assertThat(state.at("/stocks/0/quantity").asLong()).isEqualTo(stock);
    assertThat(state.path("deliveries")).hasSize(deliveryCount);
    assertThat(state.path("notifications")).hasSize(notificationCount);
  }

  private void fault(String operation, Map<String, Object> options) {
    var fault = new HashMap<String, Object>(options);
    fault.put("action", "fault");
    fault.put("operation", operation);
    fault.putIfAbsent("remaining", 1);
    fixture.control(fault);
  }
}
