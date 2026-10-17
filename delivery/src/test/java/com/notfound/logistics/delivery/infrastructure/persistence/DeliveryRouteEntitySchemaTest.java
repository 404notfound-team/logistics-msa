package com.notfound.logistics.delivery.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 로컬 실행(ddl-auto=update)처럼 엔티티 매핑으로 테이블을 만들고 경로 테이블의 제약을 확인한다. */
@DataJpaTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=create",
      "spring.sql.init.mode=always",
      "spring.sql.init.schema-locations=classpath:db/test-schema.sql"
    })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class DeliveryRouteEntitySchemaTest {
  private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

  @Container
  static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:18-alpine");

  @Autowired private JdbcTemplate jdbc;

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> DATABASE.getJdbcUrl() + "&currentSchema=delivery");
    registry.add("spring.datasource.username", DATABASE::getUsername);
    registry.add("spring.datasource.password", DATABASE::getPassword);
  }

  @Test
  void deliveryAndSequenceAreUniqueAndTheDeliveryColumnHasNoForeignKey() {
    assertThat(constraintColumns("UNIQUE", "uk_p_delivery_routes_delivery_id_sequence"))
        .containsExactly("delivery_id", "sequence");
    assertThat(
            jdbc.queryForObject(
                """
                SELECT count(*) FROM information_schema.table_constraints
                WHERE table_schema = 'delivery' AND table_name = 'p_delivery_routes'
                  AND constraint_type = 'FOREIGN KEY'
                """,
                Integer.class))
        .isZero();
  }

  @Test
  void aRouteMayReferenceADeliveryIdWithoutAPhysicalForeignKey() {
    insertRoute(UUID.randomUUID(), 1);

    assertThat(jdbc.queryForObject("SELECT count(*) FROM p_delivery_routes", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void theSameSequenceIsAllowedInDifferentDeliveries() {
    insertRoute(insertDelivery(), 1);
    insertRoute(insertDelivery(), 1);

    assertThat(jdbc.queryForObject("SELECT count(*) FROM p_delivery_routes", Integer.class))
        .isEqualTo(2);
  }

  @Test
  void theSameSequenceIsRejectedInOneDelivery() {
    UUID deliveryId = insertDelivery();
    insertRoute(deliveryId, 1);

    assertThatThrownBy(() -> insertRoute(deliveryId, 1))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private List<String> constraintColumns(String type, String name) {
    return jdbc.queryForList(
        """
        SELECT k.column_name FROM information_schema.table_constraints c
        JOIN information_schema.key_column_usage k
          ON k.constraint_schema = c.constraint_schema AND k.constraint_name = c.constraint_name
        WHERE c.table_schema = 'delivery' AND c.table_name = 'p_delivery_routes'
          AND c.constraint_type = ? AND c.constraint_name = ?
        ORDER BY k.ordinal_position
        """,
        String.class,
        type,
        name);
  }

  private UUID insertDelivery() {
    UUID id = UUID.randomUUID();
    Timestamp now = Timestamp.from(BASE);
    jdbc.update(
        """
        INSERT INTO p_deliveries (
            delivery_id, order_id, status, departure_hub_id, destination_hub_id, address,
            recipient_name, created_at, created_by, updated_at, updated_by)
        VALUES (?, ?, 'HUB_WAITING', ?, ?, '서울특별시 송파구 송파대로 55', '홍길동', ?, 'tester', ?, 'tester')
        """,
        id,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        now,
        now);
    return id;
  }

  private void insertRoute(UUID deliveryId, int sequence) {
    Timestamp now = Timestamp.from(BASE);
    jdbc.update(
        """
        INSERT INTO p_delivery_routes (
            delivery_route_id, delivery_id, sequence, departure_hub_id, arrival_hub_id,
            expected_distance_km, expected_duration_seconds, status,
            created_at, created_by, updated_at, updated_by)
        VALUES (?, ?, ?, ?, ?, 100, 3600, 'WAITING', ?, 'tester', ?, 'tester')
        """,
        UUID.randomUUID(),
        deliveryId,
        sequence,
        UUID.randomUUID(),
        UUID.randomUUID(),
        now,
        now);
  }
}
