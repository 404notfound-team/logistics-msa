package com.notfound.logistics.delivery.presentation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * POST /internal/deliveries를 실제 DB(PostgreSQL)와 가짜 Hub HTTP 서버로 끝까지 실행해 응답 명세와 저장 결과를 확인한다.
 *
 * <p>HubClient는 목으로 바꾸지 않는다. Feign이 Eureka 대신 simple discovery로 가짜 Hub를 찾아 실제 HTTP로 호출하므로 인증 헤더,
 * snake_case 응답 변환, 404/5xx 처리까지 함께 검증된다.
 */
@SpringBootTest(
    properties = {
      "eureka.client.enabled=false",
      "management.tracing.enabled=false",
      "delivery.internal-auth.service-token="
          + InternalDeliveryCreationIntegrationTest.SERVICE_TOKEN,
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.sql.init.mode=always",
      "spring.sql.init.schema-locations=classpath:db/test-schema.sql,"
          + "classpath:db/migration/V1__create_p_deliveries.sql,"
          + "classpath:db/migration/V2__create_p_delivery_routes.sql"
    })
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InternalDeliveryCreationIntegrationTest {
  static final String SERVICE_TOKEN = "test-delivery-service-token";
  private static final String ACTOR = "orders-user";

  private static final UUID SEOUL_HUB = UUID.fromString("00000000-0000-0000-0000-00000000a001");
  private static final UUID BUSAN_HUB = UUID.fromString("00000000-0000-0000-0000-00000000a002");
  private static final UUID ROUTE_ID = UUID.fromString("00000000-0000-0000-0000-00000000b001");

  @Container
  static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:18-alpine");

  private static final FakeHub HUB = FakeHub.start();

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper objectMapper;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> DATABASE.getJdbcUrl() + "&currentSchema=delivery");
    registry.add("spring.datasource.username", DATABASE::getUsername);
    registry.add("spring.datasource.password", DATABASE::getPassword);
    // Eureka 대신 이 주소를 hub 서비스 인스턴스로 쓴다.
    registry.add("spring.cloud.discovery.client.simple.instances.hub[0].uri", HUB::baseUrl);
  }

  @AfterAll
  static void stopHub() {
    HUB.stop();
  }

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM p_delivery_routes");
    jdbc.update("DELETE FROM p_deliveries");
    HUB.reset();
    HUB.addHub(SEOUL_HUB, "서울 허브");
    HUB.addHub(BUSAN_HUB, "부산 허브");
    HUB.addRoute(SEOUL_HUB, BUSAN_HUB, 90, new BigDecimal("325.123"));
  }

  @Test
  void createsDeliveryAndRouteThenRespondsCreatedWithOnlyTheSpecifiedFields() throws Exception {
    UUID orderId = UUID.randomUUID();

    String body =
        create(request(orderId, SEOUL_HUB, BUSAN_HUB, "부산시 해운대구 1", "홍길동"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.*", hasSize(3)))
            .andExpect(jsonPath("$.data.order_id").value(orderId.toString()))
            .andExpect(jsonPath("$.data.status").value("HUB_WAITING"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID deliveryId = UUID.fromString(objectMapper.readTree(body).at("/data/delivery_id").asText());

    Map<String, Object> delivery =
        jdbc.queryForMap("SELECT * FROM p_deliveries WHERE delivery_id = ?", deliveryId);
    assertThat(delivery.get("order_id")).isEqualTo(orderId);
    assertThat(delivery.get("status")).isEqualTo("HUB_WAITING");
    assertThat(delivery.get("address")).isEqualTo("부산시 해운대구 1");
    // Auditing: 작업자는 X-Actor-Username, 시각은 자동으로 채워진다.
    assertThat(delivery.get("created_by")).isEqualTo(ACTOR);
    assertThat(delivery.get("updated_by")).isEqualTo(ACTOR);
    assertThat(delivery.get("created_at")).isNotNull();

    List<Map<String, Object>> routes =
        jdbc.queryForList("SELECT * FROM p_delivery_routes WHERE delivery_id = ?", deliveryId);
    assertThat(routes).hasSize(1);
    Map<String, Object> route = routes.get(0);
    assertThat(route.get("sequence")).isEqualTo(1);
    assertThat(route.get("departure_hub_id")).isEqualTo(SEOUL_HUB);
    assertThat(route.get("arrival_hub_id")).isEqualTo(BUSAN_HUB);
    assertThat((BigDecimal) route.get("expected_distance_km"))
        .isEqualByComparingTo(new BigDecimal("325.123"));
    // Hub의 소요 시간(분)을 초로 바꿔 저장한다.
    assertThat(route.get("expected_duration_seconds")).isEqualTo(90L * 60);
    assertThat(route.get("status")).isEqualTo("WAITING");
    assertThat(route.get("created_by")).isEqualTo(ACTOR);
  }

  @Test
  void sendsServiceTokenAndActorToHubAndChecksBothHubsBeforeTheRoute() throws Exception {
    create(request(UUID.randomUUID(), SEOUL_HUB, BUSAN_HUB, "부산시 해운대구 1", "홍길동"))
        .andExpect(status().isCreated());

    assertThat(HUB.requests())
        .extracting(HubRequest::path)
        .containsExactly(
            "/internal/hubs/" + SEOUL_HUB,
            "/internal/hubs/" + BUSAN_HUB,
            "/internal/hub-routes?departure_hub_id=" + SEOUL_HUB + "&arrival_hub_id=" + BUSAN_HUB);
    assertThat(HUB.requests())
        .allSatisfy(
            request -> {
              assertThat(request.serviceToken()).isEqualTo(SERVICE_TOKEN);
              assertThat(request.actor()).isEqualTo(ACTOR);
            });
  }

  @Test
  void sameOrderWithSameContentReturnsTheExistingDeliveryWithOk() throws Exception {
    UUID orderId = UUID.randomUUID();
    String request = request(orderId, SEOUL_HUB, BUSAN_HUB, "부산시 해운대구 1", "홍길동");
    String first =
        create(request)
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    int hubCallsAfterFirst = HUB.requests().size();

    String second =
        create(request)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.*", hasSize(3)))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(dataOf(second)).isEqualTo(dataOf(first));
    assertThat(countDeliveries(orderId)).isEqualTo(1);
    // 재요청은 Hub를 다시 호출하지 않는다.
    assertThat(HUB.requests()).hasSize(hubCallsAfterFirst);
  }

  @Test
  void concurrentRequestsForTheSameOrderCreateOnlyOneDelivery() throws Exception {
    UUID orderId = UUID.randomUUID();
    String request = request(orderId, SEOUL_HUB, BUSAN_HUB, "부산시 해운대구 1", "홍길동");
    // 두 요청이 모두 중복 확인을 통과한 뒤 저장하도록, 경로 응답을 두 요청이 도착할 때까지 붙잡아 둔다.
    HUB.holdRoutesUntil(2);

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Integer> first = executor.submit(() -> statusOf(request));
      Future<Integer> second = executor.submit(() -> statusOf(request));

      // 먼저 저장한 요청은 201, 유니크 인덱스에 걸린 요청은 먼저 저장된 배송을 200으로 돌려준다.
      assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(201, 200);
    } finally {
      executor.shutdownNow();
    }
    assertThat(countDeliveries(orderId)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM p_delivery_routes", Long.class))
        .isEqualTo(1);
  }

  @Test
  void sameOrderWithDifferentContentIsConflict() throws Exception {
    UUID orderId = UUID.randomUUID();
    create(request(orderId, SEOUL_HUB, BUSAN_HUB, "부산시 해운대구 1", "홍길동"))
        .andExpect(status().isCreated());

    create(request(orderId, SEOUL_HUB, BUSAN_HUB, "부산시 수영구 2", "홍길동"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DELIVERY_ALREADY_EXISTS"));

    assertThat(countDeliveries(orderId)).isEqualTo(1);
  }

  @Test
  void invalidFieldsAreBadRequestWithSnakeCaseFieldErrors() throws Exception {
    String body =
        """
        {"departure_hub_id":"%s","destination_hub_id":"%s","address":" ","recipient_name":"홍길동"}
        """
            .formatted(SEOUL_HUB, BUSAN_HUB);

    create(body)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.field_errors[*].field", containsInAnyOrder("order_id", "address")));

    assertNothingHappened();
  }

  @Test
  void malformedJsonIsBadRequest() throws Exception {
    create("{\"order_id\":")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

    assertNothingHappened();
  }

  @Test
  void invalidUuidFormatIsBadRequest() throws Exception {
    create(
            request(null, SEOUL_HUB, BUSAN_HUB, "부산시 해운대구 1", "홍길동")
                .replace("\"order_id\":null", "\"order_id\":\"not-a-uuid\""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

    assertNothingHappened();
  }

  @Test
  void unknownHubIsNotFoundAndNothingIsSaved() throws Exception {
    UUID unknownHub = UUID.randomUUID();

    create(request(UUID.randomUUID(), SEOUL_HUB, unknownHub, "부산시 해운대구 1", "홍길동"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("HUB_NOT_FOUND"));

    assertThat(countAllDeliveries()).isZero();
    assertThat(HUB.requests())
        .extracting(HubRequest::path)
        .noneMatch(path -> path.startsWith("/internal/hub-routes"));
  }

  @Test
  void missingRouteIsNotFoundAndNothingIsSaved() throws Exception {
    create(request(UUID.randomUUID(), BUSAN_HUB, SEOUL_HUB, "서울시 송파구 1", "홍길동"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("HUB_ROUTE_NOT_FOUND"));

    assertThat(countAllDeliveries()).isZero();
  }

  @Test
  void hubFailureIsServiceUnavailableAndNothingIsSaved() throws Exception {
    HUB.failWithServerError();

    create(request(UUID.randomUUID(), SEOUL_HUB, BUSAN_HUB, "부산시 해운대구 1", "홍길동"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("HUB_UNAVAILABLE"));

    assertThat(countAllDeliveries()).isZero();
  }

  @Test
  void sameDepartureAndDestinationHubSavesDeliveryWithoutRoutes() throws Exception {
    UUID orderId = UUID.randomUUID();

    create(request(orderId, SEOUL_HUB, SEOUL_HUB, "서울시 송파구 1", "홍길동"))
        .andExpect(status().isCreated());

    assertThat(countDeliveries(orderId)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM p_delivery_routes", Long.class)).isZero();
    // 허브 존재 확인은 한 번만 하고, 경로는 조회하지 않는다.
    assertThat(HUB.requests())
        .extracting(HubRequest::path)
        .containsExactly("/internal/hubs/" + SEOUL_HUB);
  }

  private ResultActions create(String body) throws Exception {
    return mockMvc.perform(
        post("/internal/deliveries")
            .header("X-Actor-Username", ACTOR)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private int statusOf(String body) throws Exception {
    return create(body).andReturn().getResponse().getStatus();
  }

  private static String request(
      UUID orderId, UUID departureHubId, UUID destinationHubId, String address, String recipient) {
    return """
        {"order_id":%s,"departure_hub_id":"%s","destination_hub_id":"%s",\
        "address":"%s","recipient_name":"%s"}
        """
        .formatted(
            orderId == null ? "null" : "\"" + orderId + "\"",
            departureHubId,
            destinationHubId,
            address,
            recipient);
  }

  private JsonNode dataOf(String body) throws IOException {
    return objectMapper.readTree(body).path("data");
  }

  private long countDeliveries(UUID orderId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM p_deliveries WHERE order_id = ?", Long.class, orderId);
  }

  private long countAllDeliveries() {
    return jdbc.queryForObject("SELECT count(*) FROM p_deliveries", Long.class);
  }

  // 입력 오류는 Hub 호출이나 저장 전에 거절된다.
  private void assertNothingHappened() {
    assertThat(countAllDeliveries()).isZero();
    assertThat(HUB.requests()).isEmpty();
  }

  /** 가짜 Hub가 받은 요청. path는 쿼리 문자열을 포함한다. */
  record HubRequest(String path, String serviceToken, String actor) {}

  /**
   * 테스트용 Hub 서버. GET /internal/hubs/{id}와 GET /internal/hub-routes를 등록된 데이터로 응답하고, 없으면 실제 Hub처럼
   * 404를 응답한다. 받은 요청을 기록해 헤더와 호출 순서를 검증할 수 있게 한다.
   */
  static final class FakeHub {
    private final HttpServer server;
    private final Map<UUID, String> hubs = new ConcurrentHashMap<>();
    private final Map<String, String> routes = new ConcurrentHashMap<>();
    private final List<HubRequest> requests = new CopyOnWriteArrayList<>();
    private volatile boolean serverError;
    private volatile CountDownLatch routeGate = new CountDownLatch(0);

    private FakeHub(HttpServer server) {
      this.server = server;
    }

    static FakeHub start() {
      try {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        FakeHub hub = new FakeHub(server);
        server.createContext("/", hub::handle);
        // 기본 실행기는 단일 스레드라, 경로 응답을 보류하면 동시 요청을 받지 못한다.
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        return hub;
      } catch (IOException exception) {
        throw new IllegalStateException(exception);
      }
    }

    String baseUrl() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void stop() {
      server.stop(0);
    }

    void reset() {
      hubs.clear();
      routes.clear();
      requests.clear();
      serverError = false;
      routeGate = new CountDownLatch(0);
    }

    // 경로 요청이 count개 도착할 때까지 응답을 보류한다. 동시 요청을 같은 시점에 저장 단계로 보내기 위해 쓴다.
    void holdRoutesUntil(int count) {
      routeGate = new CountDownLatch(count);
    }

    void addHub(UUID id, String name) {
      hubs.put(id, name);
    }

    void addRoute(UUID departure, UUID arrival, int durationMinutes, BigDecimal distanceKm) {
      routes.put(
          departure + ">" + arrival,
          """
          {"data":{"id":"%s","departure_hub_id":"%s","arrival_hub_id":"%s",\
          "duration_minutes":%d,"distance_km":%s}}
          """
              .formatted(ROUTE_ID, departure, arrival, durationMinutes, distanceKm));
    }

    void failWithServerError() {
      serverError = true;
    }

    List<HubRequest> requests() {
      return List.copyOf(requests);
    }

    private void handle(HttpExchange exchange) throws IOException {
      URI uri = exchange.getRequestURI();
      String path = uri.getPath();
      requests.add(
          new HubRequest(
              uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery(),
              exchange.getRequestHeaders().getFirst("X-Service-Token"),
              exchange.getRequestHeaders().getFirst("X-Actor-Username")));

      if (serverError) {
        respond(exchange, 500, "{\"code\":\"INTERNAL_ERROR\",\"message\":\"error\"}");
      } else if (path.startsWith("/internal/hubs/")) {
        UUID id = UUID.fromString(path.substring("/internal/hubs/".length()));
        String name = hubs.get(id);
        if (name == null) {
          respond(exchange, 404, "{\"code\":\"HUB_NOT_FOUND\",\"message\":\"not found\"}");
        } else {
          respond(
              exchange,
              200,
              "{\"data\":{\"id\":\"%s\",\"name\":\"%s\",\"address\":\"주소\"}}".formatted(id, name));
        }
      } else if (path.equals("/internal/hub-routes")) {
        CountDownLatch gate = routeGate;
        gate.countDown();
        try {
          gate.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
        }
        Map<String, String> query = query(uri.getRawQuery());
        String route =
            routes.get(query.get("departure_hub_id") + ">" + query.get("arrival_hub_id"));
        if (route == null) {
          respond(exchange, 404, "{\"code\":\"HUB_ROUTE_NOT_FOUND\",\"message\":\"not found\"}");
        } else {
          respond(exchange, 200, route);
        }
      } else {
        respond(exchange, 404, "{\"code\":\"NOT_FOUND\",\"message\":\"not found\"}");
      }
    }

    private static Map<String, String> query(String rawQuery) {
      Map<String, String> values = new ConcurrentHashMap<>();
      for (String pair : rawQuery == null ? new String[0] : rawQuery.split("&")) {
        String[] parts = pair.split("=", 2);
        values.put(parts[0], parts.length > 1 ? parts[1] : "");
      }
      return values;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, bytes.length);
      try (OutputStream output = exchange.getResponseBody()) {
        output.write(bytes);
      }
    }
  }
}
