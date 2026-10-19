package com.notfound.logistics.orders.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notfound.logistics.orders.OrdersApplication;
import com.notfound.logistics.orders.application.service.OrderService;
import com.notfound.logistics.orders.domain.model.OrderStatus;
import com.notfound.logistics.orders.domain.repository.OrderRepository;
import com.notfound.logistics.orders.support.OrderClientFixture;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 실제 Orders와 별도 Hub JVM을 연결하며, Company·Delivery만 테스트 서버로 대체한다. */
@SpringBootTest(
    classes = OrdersApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "eureka.client.enabled=false",
      "management.tracing.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.properties.hibernate.default_schema=public",
      "orders.recovery.enabled=false",
      "slack.enabled=false",
      "orders.clients.connect-timeout-ms=500",
      "orders.clients.read-timeout-ms=500"
    })
@EnabledIfSystemProperty(named = "orders.real-hub-jar", matches = ".+")
@EnabledIfSystemProperty(named = "orders.simulation-url", matches = ".+")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderHubHttpIntegrationTest {
  private static final String HUB = "00000000-0000-0000-0000-000000000001";
  private static final String PRODUCT = "33333333-3333-4333-8333-333333333333";

  @Container
  static final PostgreSQLContainer<?> ORDERS_DB = new PostgreSQLContainer<>("postgres:18-alpine");

  @Container
  static final PostgreSQLContainer<?> HUB_DB = new PostgreSQLContainer<>("postgres:18-alpine");

  private static Process hubProcess;
  private static String hubUrl;
  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired OrderService service;
  @Autowired OrderRepository repository;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", ORDERS_DB::getJdbcUrl);
    registry.add("spring.datasource.username", ORDERS_DB::getUsername);
    registry.add("spring.datasource.password", ORDERS_DB::getPassword);
    registry.add("orders.jwt.secret", () -> OrderClientFixture.SECRET);
    registry.add("orders.clients.service-token", () -> OrderClientFixture.SERVICE_TOKEN);
    registry.add("orders.clients.stock-url", OrderHubHttpIntegrationTest::startHub);
    registry.add("orders.clients.company-url", () -> simulation() + "/company");
    registry.add("orders.clients.delivery-url", () -> simulation() + "/delivery");
  }

  private static String simulation() {
    return System.getProperty("orders.simulation-url");
  }

  private static synchronized String startHub() {
    if (hubProcess != null) return hubUrl;
    try {
      try (var connection =
              DriverManager.getConnection(
                  HUB_DB.getJdbcUrl(), HUB_DB.getUsername(), HUB_DB.getPassword());
          var statement = connection.createStatement()) {
        statement.execute("CREATE SCHEMA hub");
      }
      int port;
      try (var socket = new ServerSocket(0)) {
        port = socket.getLocalPort();
      }
      hubUrl = "http://127.0.0.1:" + port;
      var arguments = new ArrayList<String>();
      arguments.add(
          Path.of(
                  System.getProperty("java.home"),
                  "bin",
                  System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java")
              .toString());
      arguments.add("-Xmx256m");
      arguments.add("-jar");
      arguments.add(System.getProperty("orders.real-hub-jar"));
      Map.ofEntries(
              Map.entry("server.port", Integer.toString(port)),
              Map.entry("eureka.client.enabled", "false"),
              Map.entry("management.tracing.enabled", "false"),
              Map.entry("spring.datasource.url", HUB_DB.getJdbcUrl()),
              Map.entry("spring.datasource.username", HUB_DB.getUsername()),
              Map.entry("spring.datasource.password", HUB_DB.getPassword()),
              Map.entry("spring.jpa.hibernate.ddl-auto", "create"),
              Map.entry("spring.jpa.defer-datasource-initialization", "true"),
              Map.entry("spring.sql.init.mode", "always"),
              Map.entry(
                  "spring.sql.init.data-locations",
                  "classpath:db/seed/data.sql,classpath:db/seed/hub-routes.sql"),
              Map.entry("hub.jwt.secret", OrderClientFixture.SECRET),
              Map.entry("hub.internal-auth.service-token", "hub-integration-test-service-token"),
              Map.entry("hub.internal-auth.clients.orders.token", OrderClientFixture.SERVICE_TOKEN),
              Map.entry("hub.clients.company-url", simulation() + "/company"))
          .forEach((name, value) -> arguments.add("--" + name + "=" + value));
      var log = Path.of("build", "real-hub-http.log");
      Files.createDirectories(log.getParent());
      hubProcess =
          new ProcessBuilder(arguments)
              .redirectErrorStream(true)
              .redirectOutput(log.toFile())
              .start();
      await()
          .atMost(Duration.ofSeconds(60))
          .until(
              () -> {
                if (!hubProcess.isAlive())
                  throw new IllegalStateException("Hub JVM exited; see " + log.toAbsolutePath());
                try (var http = HttpClient.newHttpClient()) {
                  return http.send(
                              HttpRequest.newBuilder(URI.create(hubUrl + "/actuator/health"))
                                  .timeout(Duration.ofSeconds(1))
                                  .GET()
                                  .build(),
                              HttpResponse.BodyHandlers.discarding())
                          .statusCode()
                      == 200;
                } catch (Exception exception) {
                  return false;
                }
              });
      return hubUrl;
    } catch (Exception exception) {
      throw new IllegalStateException("Cannot start isolated Hub JVM", exception);
    }
  }

  @AfterAll
  static void stopHub() throws Exception {
    if (hubProcess != null) {
      hubProcess.destroy();
      if (!hubProcess.waitFor(15, TimeUnit.SECONDS)) hubProcess.destroyForcibly().waitFor();
      hubProcess = null;
    }
  }

  private JdbcTemplate hubJdbc() {
    return new JdbcTemplate(
        new DriverManagerDataSource(
            HUB_DB.getJdbcUrl(), HUB_DB.getUsername(), HUB_DB.getPassword()));
  }

  @BeforeEach
  void reset() throws Exception {
    jdbc.execute("TRUNCATE TABLE p_orders");
    hubJdbc()
        .execute("TRUNCATE TABLE hub.p_stock_restorations, hub.p_stock_deductions, hub.p_stocks");
    assertThat(send("POST", simulation() + "/delivery/control", Map.of(), null, null).statusCode())
        .isEqualTo(200);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void hubCommitsAfterOrdersTimeoutAndItsReceiptControlsRecovery(boolean rejected)
      throws Exception {
    createStock(rejected ? 2 : 100);
    HttpResponse<String> response;
    try (var connection = HUB_DB.createConnection("")) {
      connection.setAutoCommit(false);
      try (var statement = connection.createStatement()) {
        statement.execute("LOCK TABLE hub.p_stock_deductions IN SHARE MODE");
      }
      response = send("POST", ordersUrl(), body(), "alice", UUID.randomUUID());
      assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
      Thread.sleep(1700);
      assertThat(
              hubJdbc().queryForObject("SELECT count(*) FROM hub.p_stock_deductions", Long.class))
          .isZero();
      connection.rollback();
    }
    UUID order = orderId(response);
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () ->
                assertThat(
                        hubJdbc()
                            .queryForObject(
                                "SELECT count(*) FROM hub.p_stock_deductions WHERE order_id=?",
                                Long.class,
                                order))
                    .isEqualTo(1));
    if (rejected)
      assertThat(
              send("POST", stockUrl() + "/add", Map.of("quantity", 98), "admin", null).statusCode())
          .isEqualTo(200);
    service.recover();
    assertThat(repository.findById(order).orElseThrow().getStatus())
        .isEqualTo(rejected ? OrderStatus.FAILED : OrderStatus.CONFIRMED);
    assertThat(stockQuantity()).isEqualTo(rejected ? 100 : 97);
    assertThat(hubJdbc().queryForObject("SELECT count(*) FROM hub.p_stock_deductions", Long.class))
        .isEqualTo(1);
  }

  @Test
  void realHubRestorationAfterLostAcknowledgementCompletesCancellationOnce() throws Exception {
    createStock(100);
    var created = send("POST", ordersUrl(), body(), "alice", UUID.randomUUID());
    assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
    UUID order = orderId(created);
    try (var connection = HUB_DB.createConnection("")) {
      connection.setAutoCommit(false);
      try (var statement = connection.createStatement()) {
        statement.execute("LOCK TABLE hub.p_stock_restorations IN SHARE MODE");
      }
      var pending = send("POST", ordersUrl() + "/" + order + "/cancel", null, "admin", null);
      assertThat(pending.statusCode()).as(pending.body()).isEqualTo(202);
      Thread.sleep(1700);
      assertThat(
              hubJdbc().queryForObject("SELECT count(*) FROM hub.p_stock_restorations", Long.class))
          .isZero();
      connection.rollback();
    }
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () ->
                assertThat(
                        hubJdbc()
                            .queryForObject(
                                "SELECT count(*) FROM hub.p_stock_restorations", Long.class))
                    .isEqualTo(1));
    service.recover();
    assertThat(repository.findById(order).orElseThrow().getStatus())
        .isEqualTo(OrderStatus.CANCELLED);
    assertThat(stockQuantity()).isEqualTo(100);
    assertThat(
            hubJdbc().queryForObject("SELECT count(*) FROM hub.p_stock_restorations", Long.class))
        .isEqualTo(1);
    assertThat(
            hubJdbc()
                .queryForObject(
                    "SELECT created_by FROM hub.p_stock_restorations WHERE order_id=?",
                    String.class,
                    order))
        .isEqualTo("admin");
  }

  private void createStock(int quantity) throws Exception {
    var response =
        send(
            "POST",
            hubUrl + "/api/stocks",
            Map.of("hub_id", HUB, "product_id", PRODUCT, "quantity", quantity),
            "admin",
            null);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
  }

  private String ordersUrl() {
    return "http://127.0.0.1:" + port + "/api/orders";
  }

  private String stockUrl() {
    return hubUrl + "/api/hubs/" + HUB + "/products/" + PRODUCT + "/stocks";
  }

  private long stockQuantity() throws Exception {
    return json(send("GET", stockUrl(), null, "alice", null)).at("/data/quantity").asLong();
  }

  private Map<String, Object> body() {
    return Map.of(
        "receiver_company_id",
        "22222222-2222-4222-8222-222222222222",
        "supplier_company_id",
        "11111111-1111-4111-8111-111111111111",
        "product_id",
        PRODUCT,
        "quantity",
        3,
        "delivery_address",
        "HTTP test",
        "recipient_name",
        "recipient");
  }

  private JsonNode json(HttpResponse<String> response) throws Exception {
    return mapper.readTree(response.body());
  }

  private UUID orderId(HttpResponse<String> response) throws Exception {
    return UUID.fromString(json(response).at("/data/order_id").asText());
  }

  private HttpResponse<String> send(String method, String url, Object body, String actor, UUID key)
      throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json");
    if (actor != null)
      builder.header(
          "Authorization",
          "Bearer " + OrderClientFixture.token(actor, actor.equals("admin") ? "MASTER" : "USER"));
    if (key != null) builder.header("Idempotency-Key", key.toString());
    builder.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
    try (var http = HttpClient.newHttpClient()) {
      return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
  }
}
