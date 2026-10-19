package com.notfound.logistics.orders.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notfound.logistics.orders.OrdersApplication;
import com.notfound.logistics.orders.domain.model.OrderStatus;
import com.notfound.logistics.orders.domain.repository.OrderRepository;
import com.notfound.logistics.orders.support.OrderClientFixture;
import com.notfound.logistics.orders.support.OrderClientTestConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class OrderRestartIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> DATABASE =
      new PostgreSQLContainer<>("postgres:18-alpine")
          .withLabel("com.notfound.logistics.task", "orders-complete-01a13e9f");

  @Test
  void aNewApplicationInstanceAutomaticallyRecoversPersistedInputAndIgnoresLiveProcessing()
      throws Exception {
    try (var fixture = new OrderClientFixture();
        var http = HttpClient.newHttpClient()) {
      fixture.timeout(300);
      String jwt = fixture.token("alice", "USER");
      UUID key = UUID.randomUUID();
      String body =
          new ObjectMapper()
              .writeValueAsString(
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
                      "서울",
                      "recipient_name",
                      "수령인"));
      UUID id;
      try (var first = application(fixture, "create", false)) {
        int port = first.getWebServer().getPort();
        fixture.control(
            Map.of(
                "action",
                "fault",
                "operation",
                "delivery.create",
                "remaining",
                1,
                "delay_before_ms",
                1800));
        var request =
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/orders"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + jwt)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", key.toString())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        id =
            UUID.fromString(
                new ObjectMapper().readTree(response.body()).at("/data/order_id").asText());
        assertThat(first.getBean(OrderRepository.class).findById(id).orElseThrow().getStatus())
            .isEqualTo(OrderStatus.RECOVERY_REQUIRED);
      }
      fixture.awaitIdle();
      fixture.control(
          Map.of(
              "action",
              "company_hub",
              "company_id",
              OrderClientFixture.SUPPLIER,
              "hub_id",
              "00000000-0000-0000-0000-000000000099"));
      fixture.control(Map.of("action", "product", "name", "바뀐 상품", "price", "30000.00"));
      try (var restarted = application(fixture, "validate", true)) {
        var repository = restarted.getBean(OrderRepository.class);
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline
            && repository.findById(id).orElseThrow().getStatus() != OrderStatus.CONFIRMED)
          Thread.sleep(25);
        var order = repository.findById(id).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getProductName()).isEqualTo("테스트 상품");
        assertThat(order.getUnitPrice()).isEqualTo(10000L);
        assertThat(order.getDepartureHubId().toString()).endsWith("000000000002");
        assertThat(order.getCreatedBy()).isEqualTo("alice");
        assertThat(order.getUpdatedBy()).isEqualTo("alice");
        assertThat(fixture.state().at("/stocks/0/quantity").asLong()).isEqualTo(997);
        assertThat(fixture.state().path("deliveries")).hasSize(1);
        assertThat(fixture.state().path("notifications")).hasSize(1);
        // While a request owns a CONFIRMED -> CANCELLING transition, automatic recovery must skip
        // it.
        fixture.control(
            Map.of(
                "action",
                "fault",
                "operation",
                "delivery.cancel",
                "remaining",
                1,
                "delay_after_ms",
                150));
        var cancel =
            HttpRequest.newBuilder(
                    URI.create(
                        "http://127.0.0.1:"
                            + restarted.getWebServer().getPort()
                            + "/api/orders/"
                            + id
                            + "/cancel"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + jwt)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        var cancelled =
            CompletableFuture.supplyAsync(
                    () -> {
                      try {
                        return http.send(cancel, HttpResponse.BodyHandlers.ofString());
                      } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                      }
                    })
                .get(10, TimeUnit.SECONDS);
        assertThat(cancelled.statusCode()).as(cancelled.body()).isEqualTo(200);
        assertThat(fixture.state().at("/counts/delivery.cancel").asInt()).isEqualTo(1);
        assertThat(fixture.state().at("/counts/stock.deduct").asInt()).isEqualTo(1);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void restartRecoversAfterLostRestorationResponseOrFailedFinalOrderCommit(boolean lostResponse)
      throws Exception {
    try (var fixture = new OrderClientFixture();
        var http = HttpClient.newHttpClient()) {
      fixture.timeout(300);
      String user = fixture.token("alice", "USER");
      String admin = fixture.token("admin", "MASTER");
      String expired = OrderClientFixture.token("admin", "MASTER", -3600);
      UUID id;
      try (var first = application(fixture, "create", false)) {
        String base = "http://127.0.0.1:" + first.getWebServer().getPort();
        var created =
            http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/orders"))
                    .header("Authorization", "Bearer " + user)
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .POST(
                        HttpRequest.BodyPublishers.ofString(
                            new ObjectMapper()
                                .writeValueAsString(
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
                                        "서울",
                                        "recipient_name",
                                        "수령인"))))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        id =
            UUID.fromString(
                new ObjectMapper().readTree(created.body()).at("/data/order_id").asText());
        var jdbc = first.getBean(JdbcTemplate.class);
        if (lostResponse) {
          fixture.control(
              Map.of(
                  "action",
                  "fault",
                  "operation",
                  "stock.restore",
                  "remaining",
                  1,
                  "delay_after_ms",
                  1800));
        } else {
          jdbc.execute(
              "CREATE FUNCTION reject_cancel() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.status='CANCELLED' THEN RAISE EXCEPTION 'fixture'; END IF; RETURN NEW; END $$");
          jdbc.execute(
              "CREATE TRIGGER reject_cancel BEFORE UPDATE ON p_orders FOR EACH ROW EXECUTE FUNCTION reject_cancel()");
        }
        try {
          var response =
              http.send(
                  HttpRequest.newBuilder(URI.create(base + "/api/orders/" + id + "/cancel"))
                      .header("Authorization", "Bearer " + admin)
                      .POST(HttpRequest.BodyPublishers.noBody())
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
          assertThat(response.statusCode()).as(response.body()).isEqualTo(lostResponse ? 202 : 500);
          var pending = first.getBean(OrderRepository.class).findById(id).orElseThrow();
          assertThat(pending.getStatus())
              .isEqualTo(lostResponse ? OrderStatus.RECOVERY_REQUIRED : OrderStatus.CANCELLING);
          assertThat(pending.awaitingStockRestoration()).isTrue();
          assertThat(pending.getUpdatedBy()).isEqualTo("admin");
          assertThat(fixture.state().at("/stocks/0/quantity").asLong()).isEqualTo(1000);
          assertThat(fixture.state().path("restorations")).hasSize(1);
          var deletion =
              http.send(
                  HttpRequest.newBuilder(URI.create(base + "/api/orders/" + id))
                      .header("Authorization", "Bearer " + admin)
                      .DELETE()
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
          assertThat(deletion.statusCode()).isEqualTo(409);
        } finally {
          if (!lostResponse) {
            jdbc.execute("DROP TRIGGER reject_cancel ON p_orders");
            jdbc.execute("DROP FUNCTION reject_cancel()");
          }
        }
      }
      fixture.awaitIdle();
      try (var restarted = application(fixture, "validate", true)) {
        var repository = restarted.getBean(OrderRepository.class);
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline
            && repository.findById(id).orElseThrow().getStatus() != OrderStatus.CANCELLED)
          Thread.sleep(25);
        var cancelled = repository.findById(id).orElseThrow();
        assertThat(cancelled.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelled.getCreatedBy()).isEqualTo("alice");
        assertThat(cancelled.getUpdatedBy()).isEqualTo("admin");
        assertThat(fixture.state().at("/stocks/0/quantity").asLong()).isEqualTo(1000);
        assertThat(fixture.state().at("/counts/stock.restore").asInt()).isEqualTo(2);
        assertThat(fixture.state().at("/counts/stock.deduct").asInt()).isEqualTo(1);
        assertThat(fixture.state().at("/counts/delivery.cancel").asInt()).isEqualTo(1);
        assertThat(fixture.state().at("/actors/stock.restore").asText()).isEqualTo("admin");
        assertThat(fixture.state().path("restorations")).hasSize(1);
        var unauthorized =
            http.send(
                HttpRequest.newBuilder(
                        URI.create(
                            "http://127.0.0.1:"
                                + restarted.getWebServer().getPort()
                                + "/api/orders/"
                                + id))
                    .header("Authorization", "Bearer " + expired)
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(unauthorized.statusCode()).isEqualTo(401);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void deductionRecoveryAndPermanentBlockSurviveAnOrdersRestart(boolean blocked) throws Exception {
    try (var fixture = new OrderClientFixture();
        var http = HttpClient.newHttpClient()) {
      fixture.timeout(300);
      UUID id;
      try (var first = application(fixture, "create", false)) {
        fixture.control(
            blocked
                ? Map.of(
                    "action",
                    "fault",
                    "operation",
                    "stock.deduct",
                    "status",
                    409,
                    "code",
                    "STOCK_DEDUCTION_CONFLICT")
                : Map.of("action", "fault", "operation", "stock.deduct", "delay_after_ms", 800));
        String body =
            new ObjectMapper()
                .writeValueAsString(
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
                        "original address",
                        "recipient_name",
                        "recipient"));
        var request =
            HttpRequest.newBuilder(
                    URI.create(
                        "http://127.0.0.1:" + first.getWebServer().getPort() + "/api/orders"))
                .header("Authorization", "Bearer " + fixture.token("alice", "USER"))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        id =
            UUID.fromString(
                new ObjectMapper().readTree(response.body()).at("/data/order_id").asText());
      }
      fixture.awaitIdle();
      fixture.control(
          Map.of(
              "action",
              "company_hub",
              "company_id",
              OrderClientFixture.SUPPLIER,
              "hub_id",
              "00000000-0000-0000-0000-000000000099"));
      try (var restarted = application(fixture, "validate", true)) {
        var repository = restarted.getBean(OrderRepository.class);
        if (blocked) {
          Thread.sleep(300);
          var order = repository.findById(id).orElseThrow();
          assertThat(order.isRecoveryBlocked()).isTrue();
          assertThat(order.getStatus()).isEqualTo(OrderStatus.RECOVERY_REQUIRED);
          assertThat(fixture.state().at("/counts/stock.deduct").asInt()).isEqualTo(1);
          assertThat(fixture.state().at("/stocks/0/quantity").asLong()).isEqualTo(1000);
        } else {
          org.awaitility.Awaitility.await()
              .atMost(Duration.ofSeconds(10))
              .untilAsserted(
                  () ->
                      assertThat(repository.findById(id).orElseThrow().getStatus())
                          .isEqualTo(OrderStatus.CONFIRMED));
          var order = repository.findById(id).orElseThrow();
          assertThat(order.getDepartureHubId().toString()).endsWith("000000000002");
          assertThat(order.getCreatedBy()).isEqualTo("alice");
          assertThat(order.getUpdatedBy()).isEqualTo("alice");
          assertThat(fixture.state().at("/counts/stock.deduct").asInt()).isEqualTo(2);
          assertThat(fixture.state().at("/stocks/0/quantity").asLong()).isEqualTo(997);
          assertThat(fixture.state().path("deliveries")).hasSize(1);
        }
      }
    }
  }

  @Test
  void schemaUpdatePreservesLegacyOrdersWithoutMarkingThemAsRecorded() throws Exception {
    try (var fixture = new OrderClientFixture();
        var http = HttpClient.newHttpClient()) {
      UUID id;
      try (var first = application(fixture, "create", false)) {
        String body =
            new ObjectMapper()
                .writeValueAsString(
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
                        "legacy",
                        "recipient_name",
                        "recipient"));
        var response =
            http.send(
                HttpRequest.newBuilder(
                        URI.create(
                            "http://127.0.0.1:" + first.getWebServer().getPort() + "/api/orders"))
                    .header("Authorization", "Bearer " + fixture.token("alice", "USER"))
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        id =
            UUID.fromString(
                new ObjectMapper().readTree(response.body()).at("/data/order_id").asText());
      }
      // Only this disposable test database is reduced to the pre-feature schema.
      try (var connection = DATABASE.createConnection("");
          var statement = connection.createStatement()) {
        statement.execute(
            "ALTER TABLE p_orders DROP COLUMN stock_contract_version, DROP COLUMN recovery_phase, DROP COLUMN recovery_attempts, DROP COLUMN next_recovery_at, DROP COLUMN recovery_blocked");
        try (var update =
            connection.prepareStatement(
                "UPDATE p_orders SET status='PROCESSING', recovery_action='CREATE', last_error='STOCK_RESULT_UNKNOWN' WHERE id=?")) {
          update.setObject(1, id);
          update.executeUpdate();
        }
      }
      try (var updated = application(fixture, "update", false)) {
        var repository = updated.getBean(OrderRepository.class);
        var before = repository.findById(id).orElseThrow();
        assertThat(before.getStockContractVersion()).isNull();
        assertThat(before.getRecoveryAttempts()).isZero();
        assertThat(before.isRecoveryBlocked()).isFalse();
        updated
            .getBean(com.notfound.logistics.orders.application.service.OrderService.class)
            .recover();
        var after = repository.findById(id).orElseThrow();
        assertThat(after.isRecoveryBlocked()).isTrue();
        assertThat(after.getLastError()).isEqualTo("LEGACY_STOCK_CONTRACT");
        assertThat(after.getStockContractVersion()).isNull();
        assertThat(fixture.state().at("/counts/stock.deduct").asInt()).isEqualTo(1);
        assertThat(fixture.state().at("/stocks/0/quantity").asLong()).isEqualTo(997);
      }
      try (var validated = application(fixture, "validate", false)) {
        assertThat(
                validated
                    .getBean(OrderRepository.class)
                    .findById(id)
                    .orElseThrow()
                    .isRecoveryBlocked())
            .isTrue();
      }
    }
  }

  private ServletWebServerApplicationContext application(
      OrderClientFixture fixture, String ddl, boolean recover) {
    var arguments = new ArrayList<String>();
    Map.ofEntries(
            Map.entry("server.port", "0"),
            Map.entry("eureka.client.enabled", "false"),
            Map.entry("management.tracing.enabled", "false"),
            Map.entry("spring.datasource.url", DATABASE.getJdbcUrl()),
            Map.entry("spring.datasource.username", DATABASE.getUsername()),
            Map.entry("spring.datasource.password", DATABASE.getPassword()),
            Map.entry("spring.jpa.hibernate.ddl-auto", ddl),
            Map.entry("spring.jpa.properties.hibernate.default_schema", "public"),
            Map.entry("orders.jwt.secret", OrderClientFixture.SECRET),
            Map.entry("orders.clients.service-token", OrderClientFixture.SERVICE_TOKEN),
            Map.entry("orders.clients.read-timeout-ms", "300"),
            Map.entry("orders.recovery.enabled", Boolean.toString(recover)),
            Map.entry("orders.recovery.delay-ms", "50"))
        .forEach((name, value) -> arguments.add("--" + name + "=" + value));
    arguments.add("--orders.recovery.retry-delay-ms=0");
    return (ServletWebServerApplicationContext)
        new SpringApplicationBuilder(OrdersApplication.class, OrderClientTestConfiguration.class)
            .initializers(
                context ->
                    context.getBeanFactory().registerSingleton("orderClientFixture", fixture))
            .run(arguments.toArray(String[]::new));
  }
}
