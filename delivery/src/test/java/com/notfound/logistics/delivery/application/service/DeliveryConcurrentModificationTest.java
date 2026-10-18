package com.notfound.logistics.delivery.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.notfound.logistics.delivery.application.dto.DeliveryActor;
import com.notfound.logistics.delivery.domain.model.Delivery;
import com.notfound.logistics.delivery.domain.model.DeliveryStatus;
import com.notfound.logistics.delivery.domain.repository.DeliveryRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 같은 배송을 읽은 두 트랜잭션이 엇갈려 수정하면 먼저 커밋한 쪽만 반영되는지 확인한다. */
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
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DeliveryConcurrentModificationTest {
  private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");
  private static final UUID ASSIGNEE_ID = UUID.fromString("00000000-0000-0000-0000-000000000007");
  private static final DeliveryActor MASTER = new DeliveryActor(UUID.randomUUID(), true);

  @Container
  static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:18-alpine");

  @Autowired private DeliveryCommandService service;
  @Autowired private DeliveryRepository deliveryRepository;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate jdbc;

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> DATABASE.getJdbcUrl() + "&currentSchema=delivery");
    registry.add("spring.datasource.username", DATABASE::getUsername);
    registry.add("spring.datasource.password", DATABASE::getPassword);
  }

  @Test
  void assignmentValidatedAgainstAStaleStatusFailsWhenTheStatusChangeCommitsFirst() {
    UUID deliveryId = insertHubWaitingDelivery();
    TransactionTemplate assignment = new TransactionTemplate(transactionManager);
    TransactionTemplate statusChange = new TransactionTemplate(transactionManager);
    statusChange.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

    // 배정 요청이 HUB_WAITING을 보고 검증을 통과한 사이에 상태 변경 요청이 HUB_MOVING으로 먼저 커밋한다.
    assertThatThrownBy(
            () ->
                assignment.executeWithoutResult(
                    status -> {
                      Delivery stale = deliveryRepository.findById(deliveryId).orElseThrow();
                      stale.assign(ASSIGNEE_ID);
                      statusChange.executeWithoutResult(
                          inner ->
                              service.modify(deliveryId, DeliveryStatus.HUB_MOVING, null, MASTER));
                    }))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);

    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT status, delivery_manager_id, version FROM p_deliveries WHERE delivery_id = ?",
            deliveryId);
    assertThat(row.get("status")).isEqualTo("HUB_MOVING");
    assertThat(row.get("delivery_manager_id")).isNull();
    assertThat(row.get("version")).isEqualTo(1L);
  }

  @Test
  void eachSuccessfulModificationIncreasesTheVersion() {
    UUID deliveryId = insertHubWaitingDelivery();

    service.modify(deliveryId, null, ASSIGNEE_ID, MASTER);
    service.modify(deliveryId, DeliveryStatus.HUB_MOVING, null, MASTER);

    assertThat(
            jdbc.queryForObject(
                "SELECT version FROM p_deliveries WHERE delivery_id = ?", Long.class, deliveryId))
        .isEqualTo(2L);
  }

  private UUID insertHubWaitingDelivery() {
    UUID id = UUID.randomUUID();
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
        Timestamp.from(BASE),
        Timestamp.from(BASE));
    return id;
  }
}
