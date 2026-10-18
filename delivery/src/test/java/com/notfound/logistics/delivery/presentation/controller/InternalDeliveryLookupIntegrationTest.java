package com.notfound.logistics.delivery.presentation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.notfound.logistics.delivery.domain.model.DeliveryStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * GET /internal/deliveries/by-order/{order_id}를 실제 DB(PostgreSQL)와 서비스 인증까지 거쳐 실행해 응답 명세를 확인한다. 배송은
 * JDBC로 직접 넣어 Hub 호출 없이 조회만 검증한다.
 */
@SpringBootTest(
    properties = {
      "eureka.client.enabled=false",
      "management.tracing.enabled=false",
      // Hub Feign 클라이언트는 서비스 토큰이 비어 있으면 기동하지 않는다.
      "delivery.internal-auth.service-token=test-delivery-service-token",
      // 수신 측 인증: orders만 주문별 조회가 허용되고, company는 인증은 되지만 허용 API가 없다.
      "delivery.internal-auth.clients.orders.token="
          + InternalDeliveryLookupIntegrationTest.ORDERS_TOKEN,
      "delivery.internal-auth.clients.company.token="
          + InternalDeliveryLookupIntegrationTest.COMPANY_TOKEN
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InternalDeliveryLookupIntegrationTest {
  static final String ORDERS_TOKEN = "test-orders-service-token";
  static final String COMPANY_TOKEN = "test-company-service-token";
  private static final String PATH = "/internal/deliveries/by-order/{order_id}";
  private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

  @Container
  static final PostgreSQLContainer<?> DATABASE =
      new PostgreSQLContainer<>("postgres:18-alpine").withInitScript("db/test-schema.sql");

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

  @Test
  void respondsWithOnlyTheSpecifiedFieldsOfTheDeliveryOfTheOrder() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID deliveryId = insertDelivery(orderId, DeliveryStatus.HUB_MOVING, null);
    // 다른 주문의 배송은 응답에 섞이지 않는다.
    insertDelivery(UUID.randomUUID(), DeliveryStatus.DELIVERED, null);

    lookup(orderId)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.*", hasSize(3)))
        .andExpect(jsonPath("$.data.delivery_id").value(deliveryId.toString()))
        .andExpect(jsonPath("$.data.order_id").value(orderId.toString()))
        .andExpect(jsonPath("$.data.status").value("HUB_MOVING"));
  }

  @ParameterizedTest
  @EnumSource(DeliveryStatus.class)
  void respondsWithTheCurrentStatusOfTheDelivery(DeliveryStatus deliveryStatus) throws Exception {
    UUID orderId = UUID.randomUUID();
    insertDelivery(orderId, deliveryStatus, null);

    lookup(orderId)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value(deliveryStatus.name()));
  }

  @Test
  void orderWithoutADeliveryIsNotFound() throws Exception {
    insertDelivery(UUID.randomUUID(), DeliveryStatus.HUB_WAITING, null);

    lookup(UUID.randomUUID())
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("DELIVERY_NOT_FOUND"))
        .andExpect(jsonPath("$.message").value("배송을 찾을 수 없습니다."))
        .andExpect(jsonPath("$.field_errors").isEmpty());
  }

  @Test
  void deletedDeliveryIsNotFound() throws Exception {
    UUID orderId = UUID.randomUUID();
    insertDelivery(orderId, DeliveryStatus.HUB_WAITING, BASE);

    lookup(orderId)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("DELIVERY_NOT_FOUND"));
  }

  @Test
  void deletedDeliveryIsIgnoredWhenTheOrderHasANewDelivery() throws Exception {
    UUID orderId = UUID.randomUUID();
    insertDelivery(orderId, DeliveryStatus.HUB_WAITING, BASE);
    UUID current = insertDelivery(orderId, DeliveryStatus.HUB_MOVING, null);

    lookup(orderId)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.delivery_id").value(current.toString()))
        .andExpect(jsonPath("$.data.status").value("HUB_MOVING"));
  }

  @Test
  void upperCaseOrderIdIsTheSameOrder() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID deliveryId = insertDelivery(orderId, DeliveryStatus.HUB_WAITING, null);

    perform(get(PATH, orderId.toString().toUpperCase(Locale.ROOT)), ORDERS_TOKEN)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.delivery_id").value(deliveryId.toString()))
        .andExpect(jsonPath("$.data.order_id").value(orderId.toString()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"not-a-uuid", "123", "00000000-0000-0000-0000-00000000000g"})
  void malformedOrderIdIsRejectedWithTheCommonErrorFormat(String orderId) throws Exception {
    perform(get(PATH, orderId), ORDERS_TOKEN)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.field_errors.length()").value(1))
        .andExpect(jsonPath("$.field_errors[0].field").value("order_id"));
  }

  @Test
  void missingOrderIdDoesNotReachTheLookup() throws Exception {
    perform(get("/internal/deliveries/by-order/"), ORDERS_TOKEN)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void actorHeaderIsNotRequiredForALookup() throws Exception {
    UUID orderId = UUID.randomUUID();
    insertDelivery(orderId, DeliveryStatus.HUB_WAITING, null);

    // perform()은 X-Actor-Username을 붙이지 않는다. 조회는 감사 필드를 쓰지 않으므로 원 요청자 없이도 응답한다.
    lookup(orderId).andExpect(status().isOk());
  }

  @Test
  void lookupDoesNotChangeTheDelivery() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID deliveryId = insertDelivery(orderId, DeliveryStatus.HUB_WAITING, null);

    lookup(orderId).andExpect(status().isOk());

    assertThat(
            jdbc.queryForObject(
                "SELECT updated_at FROM p_deliveries WHERE delivery_id = ?",
                Timestamp.class,
                deliveryId))
        .isEqualTo(Timestamp.from(BASE));
  }

  @Test
  void missingServiceTokenIsUnauthorized() throws Exception {
    UUID orderId = UUID.randomUUID();
    insertDelivery(orderId, DeliveryStatus.HUB_WAITING, null);

    perform(get(PATH, orderId), null)
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
  }

  @Test
  void unknownServiceTokenIsUnauthorized() throws Exception {
    UUID orderId = UUID.randomUUID();
    insertDelivery(orderId, DeliveryStatus.HUB_WAITING, null);

    perform(get(PATH, orderId), "unknown-service-token")
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
  }

  @Test
  void serviceWithoutPermissionIsForbidden() throws Exception {
    UUID orderId = UUID.randomUUID();
    insertDelivery(orderId, DeliveryStatus.HUB_WAITING, null);

    perform(get(PATH, orderId), COMPANY_TOKEN)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  void ordersCannotCallTheLookupPathWithAnotherMethod() throws Exception {
    // 허용 API는 메서드까지 일치해야 한다.
    perform(post(PATH, UUID.randomUUID()), ORDERS_TOKEN)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  private ResultActions lookup(UUID orderId) throws Exception {
    return perform(get(PATH, orderId), ORDERS_TOKEN);
  }

  private ResultActions perform(MockHttpServletRequestBuilder request, String token)
      throws Exception {
    if (token != null) {
      request.header("X-Service-Token", token);
    }
    return mockMvc.perform(request);
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
}
