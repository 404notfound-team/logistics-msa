package com.notfound.logistics.orders.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notfound.logistics.orders.OrdersApplication;
import com.notfound.logistics.orders.application.service.OrderService;
import com.notfound.logistics.orders.domain.model.OrderStatus;
import com.notfound.logistics.orders.domain.repository.OrderRepository;
import com.notfound.logistics.orders.support.OrderClientFixture;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

/** 실제 Orders DB와 Feign을 사용해 로컬 Postman 시뮬레이션의 HTTP 장애를 검증한다. */
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
      "orders.recovery.retry-delay-ms=0",
      "orders.clients.connect-timeout-ms=200",
      "orders.clients.read-timeout-ms=300"
    })
@EnabledIfSystemProperty(named = "orders.simulation-url", matches = ".+")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderStockHttpRecoveryIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:18-alpine");

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired OrderService service;
  @Autowired OrderRepository repository;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
    registry.add("spring.datasource.username", DATABASE::getUsername);
    registry.add("spring.datasource.password", DATABASE::getPassword);
    registry.add("orders.jwt.secret", () -> OrderClientFixture.SECRET);
    registry.add("orders.clients.service-token", () -> OrderClientFixture.SERVICE_TOKEN);
    registry.add("orders.clients.company-url", () -> base() + "/company");
    registry.add("orders.clients.stock-url", () -> base() + "/hub");
    registry.add("orders.clients.delivery-url", () -> base() + "/delivery");
  }

  private static String base() {
    return System.getProperty("orders.simulation-url");
  }

  @BeforeEach
  void reset() throws Exception {
    jdbc.execute("TRUNCATE TABLE p_orders");
    control("hub", Map.of());
    control("delivery", Map.of());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"lost-response", "malformed-response", "before-commit-error", "html-error"})
  void realFeignRecoversUncertainDeductionWithoutDuplicatingItsEffect(String mode)
      throws Exception {
    control("hub", Map.of("mode", mode));
    UUID id = createPending();
    assertThat(repository.findById(id).orElseThrow().getStatus())
        .isEqualTo(OrderStatus.RECOVERY_REQUIRED);
    service.recover();
    assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    var state = hubState();
    assertThat(state.path("quantity").asInt()).isEqualTo(97);
    assertThat(state.path("receipts").size()).isEqualTo(1);
    assertThat(state.path("calls").asInt()).isEqualTo(2);
    service.recover();
    assertThat(hubState().path("calls").asInt()).isEqualTo(2);
  }

  @Test
  void aLostRejectionIsStillRejectedWhenInventoryArrivesBeforeRetry() throws Exception {
    control("hub", Map.of("quantity", 2, "mode", "lost-response"));
    UUID id = createPending();
    control("hub", Map.of("action", "stock", "quantity", 100));
    service.recover();
    assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.FAILED);
    assertThat(hubState().path("quantity").asInt()).isEqualTo(100);
    assertThat(hubState().path("calls").asInt()).isEqualTo(2);
  }

  @Test
  void permanentConflictsArePersistedAndExcludedFromAutomaticRetry() throws Exception {
    control("hub", Map.of("mode", "permanent-conflict"));
    UUID id = createPending();
    assertThat(repository.findById(id).orElseThrow().isRecoveryBlocked()).isTrue();
    service.recover();
    assertThat(hubState().path("calls").asInt()).isEqualTo(1);
    assertThat(hubState().path("quantity").asInt()).isEqualTo(100);
  }

  @Test
  void definiteDeliveryRejectionCompensatesThroughTheRealStockClient() throws Exception {
    control("delivery", Map.of("reject", true));
    var response = create();
    assertThat(response.statusCode()).as(response.body()).isEqualTo(502);
    assertThat(hubState().path("quantity").asInt()).isEqualTo(100);
    assertThat(hubState().path("restores").asInt()).isEqualTo(1);
  }

  private UUID createPending() throws Exception {
    var response = create();
    assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
    return UUID.fromString(mapper.readTree(response.body()).at("/data/order_id").asText());
  }

  private HttpResponse<String> create() throws Exception {
    var body =
        Map.of(
            "receiver_company_id",
            "22222222-2222-4222-8222-222222222222",
            "supplier_company_id",
            "11111111-1111-4111-8111-111111111111",
            "product_id",
            "33333333-3333-4333-8333-333333333333",
            "quantity",
            3,
            "delivery_address",
            "HTTP test",
            "recipient_name",
            "recipient");
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/orders"))
            .header("Authorization", "Bearer " + OrderClientFixture.token("alice", "USER"))
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
            .build();
    return send(request);
  }

  private void control(String member, Map<String, Object> body) throws Exception {
    var response =
        send(
            HttpRequest.newBuilder(URI.create(base() + "/" + member + "/control"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
  }

  private JsonNode hubState() throws Exception {
    return mapper.readTree(
        send(HttpRequest.newBuilder(URI.create(base() + "/hub/control")).GET().build()).body());
  }

  private HttpResponse<String> send(HttpRequest request) throws Exception {
    try (var http = HttpClient.newHttpClient()) {
      return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
  }
}
