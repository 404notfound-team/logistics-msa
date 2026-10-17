package com.notfound.logistics.delivery.presentation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * POST /internal/deliveries를 실제 DB(PostgreSQL)와 가짜 Hub로 끝까지 실행해 응답 명세와 저장 결과를 확인한다.
 *
 * <p>HubClient는 목으로 바꾸지 않는다. 가짜 Hub 컨트롤러를 테스트 서버에 함께 띄우고, Feign이 Eureka 대신 simple discovery로 그 서버를
 * 찾아 실제 HTTP로 호출하므로 인증 헤더, snake_case 응답 변환, 404/5xx 처리까지 함께 검증된다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
    properties = {
      "eureka.client.enabled=false",
      "management.tracing.enabled=false",
      "delivery.internal-auth.service-token="
          + InternalDeliveryCreationIntegrationTest.SERVICE_TOKEN,
      // 수신 측 인증: orders만 배송 생성이 허용되고, company는 인증은 되지만 허용 API가 없다.
      "delivery.internal-auth.clients.orders.token="
          + InternalDeliveryCreationIntegrationTest.ORDERS_TOKEN,
      "delivery.internal-auth.clients.company.token="
          + InternalDeliveryCreationIntegrationTest.COMPANY_TOKEN,
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
  static final String ORDERS_TOKEN = "test-orders-service-token";
  static final String COMPANY_TOKEN = "test-company-service-token";
  private static final String ACTOR = "orders-user";

  private static final UUID SEOUL_HUB = UUID.fromString("00000000-0000-0000-0000-00000000a001");
  private static final UUID BUSAN_HUB = UUID.fromString("00000000-0000-0000-0000-00000000a002");
  private static final UUID ROUTE_ID = UUID.fromString("00000000-0000-0000-0000-00000000b001");

  @Container
  static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:18-alpine");

  // 컨텍스트가 뜨기 전에 서버 포트를 정해야 discovery 주소에 넣을 수 있으므로 빈 포트를 미리 고른다.
  private static final int PORT = freePort();

  @Autowired private FakeHub hub;
  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper objectMapper;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> DATABASE.getJdbcUrl() + "&currentSchema=delivery");
    registry.add("spring.datasource.username", DATABASE::getUsername);
    registry.add("spring.datasource.password", DATABASE::getPassword);
    registry.add("server.port", () -> PORT);
    // Eureka 대신 테스트 서버(가짜 Hub 컨트롤러)를 hub 서비스 인스턴스로 쓴다.
    registry.add(
        "spring.cloud.discovery.client.simple.instances.hub[0].uri",
        () -> "http://localhost:" + PORT);
  }

  private static int freePort() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM p_delivery_routes");
    jdbc.update("DELETE FROM p_deliveries");
    hub.reset();
    hub.addHub(SEOUL_HUB, "서울 허브");
    hub.addHub(BUSAN_HUB, "부산 허브");
    hub.addRoute(SEOUL_HUB, BUSAN_HUB, 90, new BigDecimal("325.123"));
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

    assertThat(hub.requests())
        .extracting(HubRequest::path)
        .containsExactly(
            "/internal/hubs/" + SEOUL_HUB,
            "/internal/hubs/" + BUSAN_HUB,
            "/internal/hub-routes?departure_hub_id=" + SEOUL_HUB + "&arrival_hub_id=" + BUSAN_HUB);
    assertThat(hub.requests())
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
    int hubCallsAfterFirst = hub.requests().size();

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
    assertThat(hub.requests()).hasSize(hubCallsAfterFirst);
  }

  @Test
  void concurrentRequestsForTheSameOrderCreateOnlyOneDelivery() throws Exception {
    UUID orderId = UUID.randomUUID();
    String request = request(orderId, SEOUL_HUB, BUSAN_HUB, "부산시 해운대구 1", "홍길동");
    // 두 요청이 모두 중복 확인을 통과한 뒤 저장하도록, 경로 응답을 두 요청이 도착할 때까지 붙잡아 둔다.
    hub.holdRoutesUntil(2);

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
  void createdDeliveryIsReturnedByTheOrderLookupWithTheSameFields() throws Exception {
    UUID orderId = UUID.randomUUID();
    String created =
        create(request(orderId, SEOUL_HUB, BUSAN_HUB, "부산시 해운대구 1", "홍길동"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

    String found =
        lookup(orderId).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

    assertThat(dataOf(found)).isEqualTo(dataOf(created));
  }

  @Test
  void lookupDuringAnUnfinishedCreationIsNotFoundButTheDeliveryAppearsOnceItCompletes()
      throws Exception {
    UUID orderId = UUID.randomUUID();
    // 생성 요청이 Hub 경로 응답을 기다리는 동안은 아직 저장 전이다(호출 측이 타임아웃을 겪는 구간).
    hub.holdRoutesUntil(2);

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<Integer> creation =
          executor.submit(
              () -> statusOf(request(orderId, SEOUL_HUB, BUSAN_HUB, "부산시 해운대구 1", "홍길동")));
      awaitRouteRequest();

      // 이 404는 "아직 조회되는 배송 없음"일 뿐 생성 실패가 아니다.
      lookup(orderId)
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value("DELIVERY_NOT_FOUND"));

      hub.releaseRoutes();
      assertThat(creation.get(10, TimeUnit.SECONDS)).isEqualTo(201);
    } finally {
      executor.shutdownNow();
    }

    lookup(orderId)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.order_id").value(orderId.toString()))
        .andExpect(jsonPath("$.data.status").value("HUB_WAITING"));
  }

  @Test
  void lookupAfterAFailedCreationIsNotFoundAndARetryMakesTheDeliveryVisible() throws Exception {
    UUID orderId = UUID.randomUUID();
    String request = request(orderId, SEOUL_HUB, BUSAN_HUB, "부산시 해운대구 1", "홍길동");
    hub.failWithServerError();

    create(request).andExpect(status().isServiceUnavailable());
    lookup(orderId)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("DELIVERY_NOT_FOUND"));

    // 404를 확정 실패로 보지 않고 같은 내용으로 다시 요청하면 배송이 생성된다.
    hub.recover();
    String created =
        create(request)
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String found =
        lookup(orderId).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    assertThat(dataOf(found)).isEqualTo(dataOf(created));
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
    assertThat(hub.requests())
        .extracting(HubRequest::path)
        .noneMatch(path -> path.startsWith("/internal/hub-routes"));
  }

  @Test
  void missingServiceTokenIsUnauthorizedAndNothingHappens() throws Exception {
    createWithToken(null, request(UUID.randomUUID(), SEOUL_HUB, BUSAN_HUB, "부산시 해운대구 1", "홍길동"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

    assertNothingHappened();
  }

  @Test
  void unknownServiceTokenIsUnauthorizedAndNothingHappens() throws Exception {
    createWithToken(
            "unknown-service-token",
            request(UUID.randomUUID(), SEOUL_HUB, BUSAN_HUB, "부산시 해운대구 1", "홍길동"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

    assertNothingHappened();
  }

  @Test
  void serviceWithoutPermissionIsForbiddenAndNothingHappens() throws Exception {
    createWithToken(
            COMPANY_TOKEN, request(UUID.randomUUID(), SEOUL_HUB, BUSAN_HUB, "부산시 해운대구 1", "홍길동"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    assertNothingHappened();
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
    hub.failWithServerError();

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
    assertThat(hub.requests())
        .extracting(HubRequest::path)
        .containsExactly("/internal/hubs/" + SEOUL_HUB);
  }

  private ResultActions create(String body) throws Exception {
    return createWithToken(ORDERS_TOKEN, body);
  }

  private ResultActions createWithToken(String token, String body) throws Exception {
    MockHttpServletRequestBuilder request =
        post("/internal/deliveries")
            .header("X-Actor-Username", ACTOR)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
    if (token != null) {
      request.header("X-Service-Token", token);
    }
    return mockMvc.perform(request);
  }

  private ResultActions lookup(UUID orderId) throws Exception {
    return mockMvc.perform(
        get("/internal/deliveries/by-order/{order_id}", orderId)
            .header("X-Service-Token", ORDERS_TOKEN));
  }

  // 생성 요청이 Hub 경로 조회까지 도달할 때까지 기다린다.
  private void awaitRouteRequest() throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (hub.requests().stream()
        .noneMatch(request -> request.path().startsWith("/internal/hub-routes"))) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("Creation did not reach the hub route lookup");
      }
      Thread.sleep(10);
    }
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
    assertThat(hub.requests()).isEmpty();
  }

  /** 가짜 Hub가 받은 요청. path는 쿼리 문자열을 포함한다. */
  record HubRequest(String path, String serviceToken, String actor) {}

  // 테스트 컨텍스트에 가짜 Hub 컨트롤러를 등록한다. Delivery 앱과 같은 서버(포트)에서 응답한다.
  @TestConfiguration
  static class FakeHubConfiguration {
    @Bean
    FakeHub fakeHub() {
      return new FakeHub();
    }
  }

  /**
   * 테스트용 Hub. GET /internal/hubs/{id}와 GET /internal/hub-routes를 등록된 데이터로 응답하고, 없으면 실제 Hub처럼 404를
   * 응답한다. 받은 요청을 기록해 헤더와 호출 순서를 검증할 수 있게 한다.
   */
  @RestController
  static class FakeHub {
    private final Map<UUID, String> hubs = new ConcurrentHashMap<>();
    private final Map<String, String> routes = new ConcurrentHashMap<>();
    private final List<HubRequest> requests = new CopyOnWriteArrayList<>();
    private volatile boolean serverError;
    private volatile CountDownLatch routeGate = new CountDownLatch(0);

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

    // 보류 중인 경로 응답을 바로 내보낸다.
    void releaseRoutes() {
      CountDownLatch gate = routeGate;
      while (gate.getCount() > 0) {
        gate.countDown();
      }
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

    void recover() {
      serverError = false;
    }

    List<HubRequest> requests() {
      return List.copyOf(requests);
    }

    @GetMapping("/internal/hubs/{id}")
    ResponseEntity<String> hub(@PathVariable("id") UUID id, HttpServletRequest request) {
      record(request);
      if (serverError) {
        return json(500, "{\"code\":\"INTERNAL_ERROR\",\"message\":\"error\"}");
      }
      String name = hubs.get(id);
      if (name == null) {
        return json(404, "{\"code\":\"HUB_NOT_FOUND\",\"message\":\"not found\"}");
      }
      return json(
          200, "{\"data\":{\"id\":\"%s\",\"name\":\"%s\",\"address\":\"주소\"}}".formatted(id, name));
    }

    @GetMapping("/internal/hub-routes")
    ResponseEntity<String> route(
        @RequestParam("departure_hub_id") UUID departure,
        @RequestParam("arrival_hub_id") UUID arrival,
        HttpServletRequest request)
        throws InterruptedException {
      record(request);
      if (serverError) {
        return json(500, "{\"code\":\"INTERNAL_ERROR\",\"message\":\"error\"}");
      }
      CountDownLatch gate = routeGate;
      gate.countDown();
      gate.await(5, TimeUnit.SECONDS);
      String route = routes.get(departure + ">" + arrival);
      if (route == null) {
        return json(404, "{\"code\":\"HUB_ROUTE_NOT_FOUND\",\"message\":\"not found\"}");
      }
      return json(200, route);
    }

    private void record(HttpServletRequest request) {
      String query = request.getQueryString();
      requests.add(
          new HubRequest(
              query == null ? request.getRequestURI() : request.getRequestURI() + "?" + query,
              request.getHeader("X-Service-Token"),
              request.getHeader("X-Actor-Username")));
    }

    private static ResponseEntity<String> json(int status, String body) {
      return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }
  }
}
