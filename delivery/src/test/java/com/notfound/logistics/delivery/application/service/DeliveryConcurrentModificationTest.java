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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
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
      "delivery.internal-auth.service-token=test-delivery-service-token",
      "management.tracing.enabled=false"
    })
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DeliveryConcurrentModificationTest {
  private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");
  private static final UUID ASSIGNEE_ID = UUID.fromString("00000000-0000-0000-0000-000000000007");
  private static final DeliveryActor MASTER = new DeliveryActor(UUID.randomUUID(), true, false);

  @Container
  static final PostgreSQLContainer<?> DATABASE =
      new PostgreSQLContainer<>("postgres:18-alpine").withInitScript("db/test-schema.sql");

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

  @BeforeEach
  void authenticate() {
    Jwt jwt = Jwt.withTokenValue("test").header("alg", "none").subject("tester").build();
    SecurityContextHolder.getContext()
        .setAuthentication(new JwtAuthenticationToken(jwt, java.util.List.of()));
  }

  @AfterEach
  void clearAuthentication() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void aFailedTransactionRollsBackBothDeliveryAndRouteProgress() {
    UUID deliveryId = insertHubWaitingDelivery();
    jdbc.update(
        """
        INSERT INTO p_delivery_routes (
            delivery_route_id, delivery_id, sequence, departure_hub_id, arrival_hub_id,
            expected_distance_km, expected_duration_seconds, status,
            created_at, created_by, updated_at, updated_by)
        VALUES (?, ?, 1, ?, ?, 10, 600, 'WAITING', ?, 'tester', ?, 'tester')
        """,
        UUID.randomUUID(),
        deliveryId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        Timestamp.from(BASE),
        Timestamp.from(BASE));
    var transaction = new TransactionTemplate(transactionManager);

    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    status -> {
                      service.modify(deliveryId, DeliveryStatus.HUB_MOVING, null, MASTER);
                      throw new IllegalStateException("Abort after both states changed");
                    }))
        .isInstanceOf(IllegalStateException.class);

    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM p_deliveries WHERE delivery_id=?", String.class, deliveryId))
        .isEqualTo("HUB_WAITING");
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM p_delivery_routes WHERE delivery_id=?",
                String.class,
                deliveryId))
        .isEqualTo("WAITING");
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
  void cancellationPreventsAStaleDepartureFromOverwritingTheCancelledStatus() {
    UUID deliveryId = insertHubWaitingDelivery();
    TransactionTemplate departure = new TransactionTemplate(transactionManager);
    TransactionTemplate cancellation = new TransactionTemplate(transactionManager);
    cancellation.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    assertThatThrownBy(
            () ->
                departure.executeWithoutResult(
                    status -> {
                      Delivery stale = deliveryRepository.findById(deliveryId).orElseThrow();
                      stale.changeStatus(DeliveryStatus.HUB_MOVING);
                      cancellation.executeWithoutResult(inner -> service.cancel(deliveryId));
                    }))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM p_deliveries WHERE delivery_id = ?", String.class, deliveryId))
        .isEqualTo("CANCELLED");
  }

  @Test
  void committedDeparturePreventsCancellation() {
    UUID deliveryId = insertHubWaitingDelivery();
    service.modify(deliveryId, DeliveryStatus.HUB_MOVING, null, MASTER);
    assertThatThrownBy(() -> service.cancel(deliveryId))
        .isInstanceOf(
            com.notfound.logistics.delivery.domain.exception.DeliveryBusinessException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM p_deliveries WHERE delivery_id = ?", String.class, deliveryId))
        .isEqualTo("HUB_MOVING");
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
