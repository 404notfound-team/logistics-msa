package com.notfound.logistics.delivery.presentation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.notfound.logistics.delivery.domain.model.DeliveryRouteStatus;
import com.notfound.logistics.delivery.domain.model.DeliveryStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * DELETE /internal/deliveries/{id}를 실제 DB(PostgreSQL)와 서비스 인증까지 거쳐 실행해 배송·경로 논리 삭제와 응답 명세를 확인한다.
 * 배송과 경로는 JDBC로 직접 넣는다.
 */
@SpringBootTest(
    properties = {
      "eureka.client.enabled=false",
      "management.tracing.enabled=false",
      // Hub Feign 클라이언트는 서비스 토큰이 비어 있으면 기동하지 않는다.
      "delivery.internal-auth.service-token=test-delivery-service-token",
      // 수신 측 인증: orders만 배송 삭제가 허용되고, company는 인증은 되지만 허용 API가 없다.
      "delivery.internal-auth.clients.orders.token="
          + InternalDeliveryDeletionIntegrationTest.ORDERS_TOKEN,
      "delivery.internal-auth.clients.company.token="
          + InternalDeliveryDeletionIntegrationTest.COMPANY_TOKEN,
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.sql.init.mode=always",
      "spring.sql.init.schema-locations=classpath:db/test-schema.sql,"
          + "classpath:db/migration/V1__create_p_deliveries.sql,"
          + "classpath:db/migration/V2__create_p_delivery_routes.sql"
    })
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InternalDeliveryDeletionIntegrationTest {
  static final String ORDERS_TOKEN = "test-orders-service-token";
  static final String COMPANY_TOKEN = "test-company-service-token";
  private static final String PATH = "/internal/deliveries/{id}";
  private static final String ACTOR = "orders-user";
  private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

  @Container
  static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:18-alpine");

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> DATABASE.getJdbcUrl() + "&currentSchema=delivery");
    registry.add("spring.datasource.username", DATABASE::getUsername);
    registry.add("spring.datasource.password", DATABASE::getPassword);
  }

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM p_delivery_routes");
    jdbc.update("DELETE FROM p_deliveries");
  }

  @ParameterizedTest
  @EnumSource(
      value = DeliveryStatus.class,
      names = {"DELIVERED", "CANCELLED"})
  void deletesTheFinishedDeliveryAndRespondsNoContent(DeliveryStatus deliveryStatus)
      throws Exception {
    UUID deliveryId = insertDelivery(deliveryStatus, null);

    deleteDelivery(deliveryId).andExpect(status().isNoContent()).andExpect(content().string(""));

    Map<String, Object> delivery = deliveryRow(deliveryId);
    assertThat(delivery.get("deleted_at")).isNotNull();
    assertThat(delivery.get("deleted_by")).isEqualTo(ACTOR);
    // 논리 삭제이므로 상태와 생성 정보는 그대로 남는다.
    assertThat(delivery.get("status")).isEqualTo(deliveryStatus.name());
    assertThat(delivery.get("created_by")).isEqualTo("tester");
  }

  @Test
  void deletesAllRoutesOfTheDeliveryWithTheSameActorAndTime() throws Exception {
    UUID deliveryId = insertDelivery(DeliveryStatus.DELIVERED, null);
    insertRoute(deliveryId, 1, DeliveryRouteStatus.ARRIVED, null);
    insertRoute(deliveryId, 2, DeliveryRouteStatus.ARRIVED, null);

    deleteDelivery(deliveryId).andExpect(status().isNoContent());

    Object deletedAt = deliveryRow(deliveryId).get("deleted_at");
    List<Map<String, Object>> routes = routeRows(deliveryId);
    assertThat(routes).hasSize(2);
    assertThat(routes)
        .allSatisfy(
            route -> {
              assertThat(route.get("deleted_by")).isEqualTo(ACTOR);
              assertThat(route.get("deleted_at")).isEqualTo(deletedAt);
              assertThat(route.get("status")).isEqualTo("ARRIVED");
            });
  }

  @Test
  void deletedDeliveryIsNoLongerReturnedByTheOrderLookup() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID deliveryId = insertDelivery(orderId, DeliveryStatus.DELIVERED, null);

    deleteDelivery(deliveryId).andExpect(status().isNoContent());

    perform(get("/internal/deliveries/by-order/{order_id}", orderId), ORDERS_TOKEN, ACTOR)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("DELIVERY_NOT_FOUND"));
  }

  @Test
  void otherDeliveriesAndTheirRoutesAreNotDeleted() throws Exception {
    UUID deliveryId = insertDelivery(DeliveryStatus.DELIVERED, null);
    UUID other = insertDelivery(DeliveryStatus.DELIVERED, null);
    insertRoute(other, 1, DeliveryRouteStatus.ARRIVED, null);

    deleteDelivery(deliveryId).andExpect(status().isNoContent());

    assertUnchanged(other);
    assertThat(routeRows(other)).allSatisfy(route -> assertThat(route.get("deleted_at")).isNull());
  }

  @Test
  void repeatedDeleteIsNoContentAndKeepsTheFirstDeletion() throws Exception {
    UUID deliveryId = insertDelivery(DeliveryStatus.DELIVERED, null);
    insertRoute(deliveryId, 1, DeliveryRouteStatus.ARRIVED, null);
    deleteDelivery(deliveryId).andExpect(status().isNoContent());
    Map<String, Object> first = deliveryRow(deliveryId);
    List<Map<String, Object>> firstRoutes = routeRows(deliveryId);

    perform(delete(PATH, deliveryId), ORDERS_TOKEN, "other-user")
        .andExpect(status().isNoContent())
        .andExpect(content().string(""));

    assertThat(deliveryRow(deliveryId)).isEqualTo(first);
    assertThat(routeRows(deliveryId)).isEqualTo(firstRoutes);
  }

  @Test
  void deliveryDeletedBeforeIsNoContentAndNotChanged() throws Exception {
    UUID deliveryId = insertDelivery(DeliveryStatus.CANCELLED, BASE);

    deleteDelivery(deliveryId).andExpect(status().isNoContent());

    Map<String, Object> delivery = deliveryRow(deliveryId);
    assertThat(delivery.get("deleted_at")).isEqualTo(Timestamp.from(BASE));
    assertThat(delivery.get("deleted_by")).isEqualTo("tester");
  }

  @Test
  void concurrentDeleteRequestsAllSucceedAndDeleteOnce() throws Exception {
    // 쓰기 잠금으로 순서대로 처리되어, 먼저 끝난 요청이 삭제하고 나머지는 이미 삭제된 배송을 받는다.
    UUID deliveryId = insertDelivery(DeliveryStatus.DELIVERED, null);
    insertRoute(deliveryId, 1, DeliveryRouteStatus.ARRIVED, null);
    int requests = 5;
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(requests);
    try {
      Callable<Integer> request =
          () -> {
            start.await();
            return deleteDelivery(deliveryId).andReturn().getResponse().getStatus();
          };
      List<Future<Integer>> results = new ArrayList<>();
      for (int i = 0; i < requests; i++) {
        results.add(executor.submit(request));
      }
      start.countDown();
      for (Future<Integer> result : results) {
        assertThat(result.get()).isEqualTo(204);
      }
    } finally {
      executor.shutdownNow();
    }

    assertThat(deliveryRow(deliveryId).get("deleted_by")).isEqualTo(ACTOR);
    assertThat(routeRows(deliveryId))
        .singleElement()
        .satisfies(route -> assertThat(route.get("deleted_at")).isNotNull());
  }

  @ParameterizedTest
  @EnumSource(
      value = DeliveryStatus.class,
      names = {"DELIVERED", "CANCELLED"},
      mode = EnumSource.Mode.EXCLUDE)
  void deliveryInProgressIsConflictAndNothingIsDeleted(DeliveryStatus deliveryStatus)
      throws Exception {
    UUID deliveryId = insertDelivery(deliveryStatus, null);
    insertRoute(deliveryId, 1, DeliveryRouteStatus.WAITING, null);

    deleteDelivery(deliveryId)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DELIVERY_IN_PROGRESS"))
        .andExpect(jsonPath("$.message").value("진행 중인 배송은 삭제할 수 없습니다."))
        .andExpect(jsonPath("$.field_errors").isEmpty());

    assertUnchanged(deliveryId);
    assertThat(routeRows(deliveryId))
        .allSatisfy(route -> assertThat(route.get("deleted_at")).isNull());
  }

  @Test
  void unknownDeliveryIsNotFound() throws Exception {
    insertDelivery(DeliveryStatus.DELIVERED, null);

    deleteDelivery(UUID.randomUUID())
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("DELIVERY_NOT_FOUND"))
        .andExpect(jsonPath("$.message").value("배송을 찾을 수 없습니다."));
  }

  @ParameterizedTest
  @ValueSource(strings = {"not-a-uuid", "123", "00000000-0000-0000-0000-00000000000g"})
  void malformedIdIsRejectedWithTheCommonErrorFormat(String id) throws Exception {
    perform(delete(PATH, id), ORDERS_TOKEN, ACTOR)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.field_errors.length()").value(1))
        .andExpect(jsonPath("$.field_errors[0].field").value("id"));
  }

  @Test
  void missingServiceTokenIsUnauthorizedAndNothingIsDeleted() throws Exception {
    UUID deliveryId = insertDelivery(DeliveryStatus.DELIVERED, null);

    perform(delete(PATH, deliveryId), null, ACTOR)
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    assertUnchanged(deliveryId);
  }

  @Test
  void unknownServiceTokenIsUnauthorizedAndNothingIsDeleted() throws Exception {
    UUID deliveryId = insertDelivery(DeliveryStatus.DELIVERED, null);

    perform(delete(PATH, deliveryId), "unknown-service-token", ACTOR)
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    assertUnchanged(deliveryId);
  }

  @Test
  void serviceWithoutPermissionIsForbiddenAndNothingIsDeleted() throws Exception {
    UUID deliveryId = insertDelivery(DeliveryStatus.DELIVERED, null);

    perform(delete(PATH, deliveryId), COMPANY_TOKEN, ACTOR)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    assertUnchanged(deliveryId);
  }

  @Test
  void ordersCannotCallTheDeletePathWithAnotherMethod() throws Exception {
    // 허용 API는 메서드까지 일치해야 한다.
    perform(post(PATH, UUID.randomUUID()), ORDERS_TOKEN, ACTOR)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  private ResultActions deleteDelivery(UUID deliveryId) throws Exception {
    return perform(delete(PATH, deliveryId), ORDERS_TOKEN, ACTOR);
  }

  private ResultActions perform(MockHttpServletRequestBuilder request, String token, String actor)
      throws Exception {
    if (token != null) {
      request.header("X-Service-Token", token);
    }
    if (actor != null) {
      request.header("X-Actor-Username", actor);
    }
    return mockMvc.perform(request);
  }

  private void assertUnchanged(UUID deliveryId) {
    Map<String, Object> delivery = deliveryRow(deliveryId);
    assertThat(delivery.get("deleted_at")).isNull();
    assertThat(delivery.get("deleted_by")).isNull();
    assertThat(delivery.get("updated_at")).isEqualTo(Timestamp.from(BASE));
    assertThat(delivery.get("updated_by")).isEqualTo("tester");
  }

  private Map<String, Object> deliveryRow(UUID deliveryId) {
    return jdbc.queryForMap(
        """
        SELECT status, created_by, updated_at, updated_by, deleted_at, deleted_by
        FROM p_deliveries WHERE delivery_id = ?
        """,
        deliveryId);
  }

  private List<Map<String, Object>> routeRows(UUID deliveryId) {
    return jdbc.queryForList(
        """
        SELECT status, updated_at, updated_by, deleted_at, deleted_by
        FROM p_delivery_routes WHERE delivery_id = ? ORDER BY sequence
        """,
        deliveryId);
  }

  private UUID insertDelivery(DeliveryStatus status, Instant deletedAt) {
    return insertDelivery(UUID.randomUUID(), status, deletedAt);
  }

  private UUID insertDelivery(UUID orderId, DeliveryStatus status, Instant deletedAt) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO p_deliveries (
            delivery_id, order_id, status, departure_hub_id, destination_hub_id, address,
            recipient_name, created_at, created_by, updated_at, updated_by, deleted_at, deleted_by)
        VALUES (?, ?, ?, ?, ?, '부산시 해운대구 1', '홍길동', ?, 'tester', ?, 'tester', ?, ?)
        """,
        id,
        orderId,
        status.name(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        Timestamp.from(BASE),
        Timestamp.from(BASE),
        deletedAt == null ? null : Timestamp.from(deletedAt),
        deletedAt == null ? null : "tester");
    return id;
  }

  private void insertRoute(
      UUID deliveryId, int sequence, DeliveryRouteStatus status, Instant deletedAt) {
    jdbc.update(
        """
        INSERT INTO p_delivery_routes (
            delivery_route_id, delivery_id, sequence, departure_hub_id, arrival_hub_id,
            expected_distance_km, expected_duration_seconds, status, created_at, created_by,
            updated_at, updated_by, deleted_at, deleted_by)
        VALUES (?, ?, ?, ?, ?, 10.000, 600, ?, ?, 'tester', ?, 'tester', ?, ?)
        """,
        UUID.randomUUID(),
        deliveryId,
        sequence,
        UUID.randomUUID(),
        UUID.randomUUID(),
        status.name(),
        Timestamp.from(BASE),
        Timestamp.from(BASE),
        deletedAt == null ? null : Timestamp.from(deletedAt),
        deletedAt == null ? null : "tester");
  }
}
