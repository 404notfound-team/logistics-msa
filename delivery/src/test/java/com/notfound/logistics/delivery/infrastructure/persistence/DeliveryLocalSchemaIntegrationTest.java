package com.notfound.logistics.delivery.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 실제 local 프로필의 Hibernate 스키마 생성과 seed 초기화로 활성 주문의 중복을 막는지 확인한다. */
@SpringBootTest(
    properties = {
      "eureka.client.enabled=false",
      "management.tracing.enabled=false",
      "delivery.internal-auth.service-token=test-delivery-service-token"
    })
@ActiveProfiles("local")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DeliveryLocalSchemaIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> DATABASE =
      new PostgreSQLContainer<>("postgres:18-alpine").withInitScript("db/test-schema.sql");

  @Autowired private JdbcTemplate jdbc;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> DATABASE.getJdbcUrl() + "&currentSchema=delivery");
    registry.add("spring.datasource.username", DATABASE::getUsername);
    registry.add("spring.datasource.password", DATABASE::getPassword);
  }

  @Test
  void freshLocalSchemaRejectsTwoActiveDeliveriesForOneOrder() {
    UUID orderId = UUID.randomUUID();
    insert(orderId, false);
    assertThatThrownBy(() -> insert(orderId, false))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM p_deliveries WHERE order_id = ?", Long.class, orderId))
        .isEqualTo(1L);
  }

  @Test
  void deletedDeliveryDoesNotReserveTheActiveOrderKey() {
    UUID orderId = UUID.randomUUID();
    insert(orderId, true);
    insert(orderId, false);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM p_deliveries WHERE order_id = ?", Long.class, orderId))
        .isEqualTo(2L);
  }

  private void insert(UUID orderId, boolean deleted) {
    jdbc.update(
        """
        INSERT INTO p_deliveries (delivery_id, order_id, status, departure_hub_id,
          destination_hub_id, address, recipient_name, created_at, created_by,
          updated_at, updated_by, deleted_at, deleted_by)
        VALUES (?, ?, 'HUB_WAITING', ?, ?, 'test-address', 'test-recipient', now(),
          'tester', now(), 'tester', CASE WHEN ? THEN now() ELSE NULL END,
          CASE WHEN ? THEN 'tester' ELSE NULL END)
        """,
        UUID.randomUUID(),
        orderId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        deleted,
        deleted);
  }
}
