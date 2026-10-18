package com.notfound.logistics.delivery.presentation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notfound.logistics.delivery.domain.model.DeliveryRouteStatus;
import com.notfound.logistics.delivery.domain.model.DeliveryStatus;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 운영 마이그레이션으로 만든 테이블에 저장한 거리·시간 값이 엔티티와 DTO를 거쳐 JSON 응답까지 그대로 유지되는지 확인한다. */
@SpringBootTest(
    properties = {
      "eureka.client.enabled=false",
      "management.tracing.enabled=false",
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.sql.init.mode=always",
      "spring.sql.init.schema-locations=classpath:db/test-schema.sql,"
          + "classpath:db/migration/V1__create_p_deliveries.sql,"
          + "classpath:db/migration/V2__create_p_delivery_routes.sql,"
          + "classpath:db/migration/V3__add_p_deliveries_version.sql"
    })
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DeliveryRouteRoundTripTest {
  private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

  @Container
  static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:18-alpine");

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper objectMapper;

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> DATABASE.getJdbcUrl() + "&currentSchema=delivery");
    registry.add("spring.datasource.username", DATABASE::getUsername);
    registry.add("spring.datasource.password", DATABASE::getPassword);
  }

  @Test
  void fractionalDistancesAndLongDurationsSurviveFromDatabaseToJsonResponse() throws Exception {
    UUID deliveryId = insertDelivery(DeliveryStatus.HUB_MOVING);
    insertRoute(
        deliveryId,
        1,
        DeliveryRouteStatus.ARRIVED,
        new BigDecimal("123456789.123"),
        new BigDecimal("12.345"),
        3_000_000_000L,
        2_999_999_999L);
    insertRoute(
        deliveryId, 2, DeliveryRouteStatus.IN_TRANSIT, new BigDecimal("0.001"), null, 1L, null);

    String body =
        mockMvc
            .perform(get("/api/deliveries/{delivery_id}", deliveryId).with(jwt()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    // 소수 값을 double로 읽으면 비교가 흐려지므로 BigDecimal 그대로 파싱한다.
    JsonNode routes =
        objectMapper
            .reader()
            .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .readTree(body)
            .path("data")
            .path("routes");
    assertThat(routes).hasSize(2);

    JsonNode first = routes.get(0);
    assertThat(first.get("expected_distance_km").decimalValue())
        .isEqualByComparingTo(new BigDecimal("123456789.123"));
    assertThat(first.get("actual_distance_km").decimalValue())
        .isEqualByComparingTo(new BigDecimal("12.345"));
    assertThat(first.get("expected_duration_seconds").longValue()).isEqualTo(3_000_000_000L);
    assertThat(first.get("actual_duration_seconds").longValue()).isEqualTo(2_999_999_999L);

    JsonNode second = routes.get(1);
    assertThat(second.get("expected_distance_km").decimalValue())
        .isEqualByComparingTo(new BigDecimal("0.001"));
    assertThat(second.get("actual_distance_km").isNull()).isTrue();
    assertThat(second.get("expected_duration_seconds").longValue()).isEqualTo(1L);
    assertThat(second.get("actual_duration_seconds").isNull()).isTrue();
  }

  @Test
  void arrivedRouteStaysArrivedWhileTheDeliveryMovesOnToTheCompany() throws Exception {
    UUID deliveryId = insertDelivery(DeliveryStatus.COMPANY_MOVING);
    insertRoute(
        deliveryId,
        1,
        DeliveryRouteStatus.ARRIVED,
        new BigDecimal("10.5"),
        new BigDecimal("10.25"),
        600L,
        620L);

    mockMvc
        .perform(get("/api/deliveries/{delivery_id}", deliveryId).with(jwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("COMPANY_MOVING"))
        .andExpect(jsonPath("$.data.routes[0].status").value("ARRIVED"));
  }

  private UUID insertDelivery(DeliveryStatus status) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO p_deliveries (
            delivery_id, order_id, status, departure_hub_id, destination_hub_id, address,
            recipient_name, created_at, created_by, updated_at, updated_by)
        VALUES (?, ?, ?, ?, ?, '서울특별시 송파구 송파대로 55', '홍길동', ?, 'tester', ?, 'tester')
        """,
        id,
        UUID.randomUUID(),
        status.name(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        Timestamp.from(BASE),
        Timestamp.from(BASE));
    return id;
  }

  private void insertRoute(
      UUID deliveryId,
      int sequence,
      DeliveryRouteStatus status,
      BigDecimal expectedDistanceKm,
      BigDecimal actualDistanceKm,
      Long expectedDurationSeconds,
      Long actualDurationSeconds) {
    jdbc.update(
        """
        INSERT INTO p_delivery_routes (
            delivery_route_id, delivery_id, sequence, departure_hub_id, arrival_hub_id,
            expected_distance_km, actual_distance_km, expected_duration_seconds,
            actual_duration_seconds, status, created_at, created_by, updated_at, updated_by)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'tester', ?, 'tester')
        """,
        UUID.randomUUID(),
        deliveryId,
        sequence,
        UUID.randomUUID(),
        UUID.randomUUID(),
        expectedDistanceKm,
        actualDistanceKm,
        expectedDurationSeconds,
        actualDurationSeconds,
        status.name(),
        Timestamp.from(BASE),
        Timestamp.from(BASE));
  }
}
