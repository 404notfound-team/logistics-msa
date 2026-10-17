package com.notfound.logistics.delivery.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.notfound.logistics.delivery.domain.model.Delivery;
import com.notfound.logistics.delivery.domain.model.DeliveryRoute;
import com.notfound.logistics.delivery.domain.model.DeliveryRouteStatus;
import com.notfound.logistics.delivery.domain.model.DeliveryStatus;
import com.notfound.logistics.delivery.domain.repository.DeliveryRepository;
import com.notfound.logistics.delivery.domain.repository.DeliverySearchCondition;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 운영과 같은 마이그레이션 SQL로 테이블을 만들고 ddl-auto=validate로 엔티티 매핑까지 확인한다. */
@DataJpaTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.sql.init.mode=always",
      "spring.sql.init.schema-locations=classpath:db/test-schema.sql,"
          + "classpath:db/migration/V1__create_p_deliveries.sql,"
          + "classpath:db/migration/V2__create_p_delivery_routes.sql"
    })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(DeliveryRepositoryAdapter.class)
@Testcontainers(disabledWithoutDocker = true)
class DeliveryRepositoryAdapterTest {
  private static final UUID HUB_A = UUID.randomUUID();
  private static final UUID HUB_B = UUID.randomUUID();
  private static final UUID HUB_C = UUID.randomUUID();
  private static final UUID MANAGER_A = UUID.randomUUID();
  private static final UUID MANAGER_B = UUID.randomUUID();
  private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");
  private static final Sort NEWEST_FIRST =
      Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"));

  @Container
  static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:18-alpine");

  @Autowired private DeliveryRepository repository;
  @Autowired private JdbcTemplate jdbc;

  private UUID first;
  private UUID second;
  private UUID third;
  private UUID deleted;

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> DATABASE.getJdbcUrl() + "&currentSchema=delivery");
    registry.add("spring.datasource.username", DATABASE::getUsername);
    registry.add("spring.datasource.password", DATABASE::getPassword);
  }

  @BeforeEach
  void insertFixtures() {
    first = insert(HUB_A, HUB_B, "서울특별시 송파구 송파대로 55", "김철수", MANAGER_A, DeliveryStatus.WAITING, 1);
    second =
        insert(HUB_A, HUB_C, "부산광역시 해운대구 100%로", "홍길동", MANAGER_B, DeliveryStatus.HUB_TRANSIT, 2);
    third = insert(HUB_B, HUB_C, "대전광역시 서구 둔산로 100", "Hong", null, DeliveryStatus.COMPLETED, 3);
    deleted = insert(HUB_A, HUB_B, "서울특별시 강남구", "삭제됨", MANAGER_A, DeliveryStatus.WAITING, 4);
    jdbc.update(
        "UPDATE p_deliveries SET deleted_at = ?, deleted_by = 'deleter' WHERE delivery_id = ?",
        Timestamp.from(BASE.plusSeconds(5)),
        deleted);
  }

  @Test
  void emptyConditionReturnsActiveDeliveriesNewestFirstAndCountsOnlyThem() {
    Page<Delivery> page =
        repository.search(condition(null, null, null, null, null, null, null), page(0, 2));

    assertThat(page.getContent()).extracting(Delivery::getId).containsExactly(third, second);
    assertThat(page.getTotalElements()).isEqualTo(3);
    assertThat(page.getContent().getFirst().getStatus()).isEqualTo(DeliveryStatus.COMPLETED);
    assertThat(page.getContent().getFirst().getCreatedAt()).isEqualTo(BASE.plusSeconds(3));
  }

  @Test
  void eachFilterNarrowsTheResultAndFiltersCombineWithAnd() {
    assertThat(ids(condition(null, null, HUB_A, null, null, null, null)))
        .containsExactly(second, first);
    assertThat(ids(condition(null, null, null, HUB_C, null, null, null)))
        .containsExactly(third, second);
    assertThat(ids(condition(null, DeliveryStatus.HUB_TRANSIT, null, null, null, null, null)))
        .containsExactly(second);
    assertThat(ids(condition(null, null, null, null, null, null, MANAGER_A)))
        .containsExactly(first);
    assertThat(ids(condition(null, null, HUB_A, HUB_C, null, null, MANAGER_B)))
        .containsExactly(second);
    assertThat(ids(condition(null, null, HUB_B, HUB_B, null, null, null))).isEmpty();
  }

  @Test
  void orderIdMatchesExactly() {
    UUID orderId =
        jdbc.queryForObject(
            "SELECT order_id FROM p_deliveries WHERE delivery_id = ?", UUID.class, second);
    assertThat(ids(condition(orderId, null, null, null, null, null, null))).containsExactly(second);
  }

  @Test
  void addressAndRecipientArePartialCaseInsensitiveAndTreatWildcardsLiterally() {
    assertThat(ids(condition(null, null, null, null, "송파", null, null))).containsExactly(first);
    assertThat(ids(condition(null, null, null, null, null, "hong", null))).containsExactly(third);
    assertThat(ids(condition(null, null, null, null, "100%", null, null))).containsExactly(second);
    assertThat(ids(condition(null, null, null, null, "%", null, null))).containsExactly(second);
    assertThat(ids(condition(null, null, null, null, "_", null, null))).isEmpty();
  }

  @Test
  void pagePastTheEndIsEmptyButKeepsTheTotal() {
    Page<Delivery> page =
        repository.search(condition(null, null, null, null, null, null, null), page(5, 2));
    assertThat(page.getContent()).isEmpty();
    assertThat(page.getTotalElements()).isEqualTo(3);
  }

  @Test
  void pageWhoseOffsetExceedsTheJpaLimitIsEmptyButKeepsTheTotal() {
    Page<Delivery> page =
        repository.search(
            condition(null, null, null, null, null, null, null), page(Integer.MAX_VALUE, 20));
    assertThat(page.getContent()).isEmpty();
    assertThat(page.getTotalElements()).isEqualTo(3);
    assertThat(page.getNumber()).isEqualTo(Integer.MAX_VALUE);
  }

  @Test
  void findByIdReturnsAnActiveDeliveryButNotADeletedOrUnknownOne() {
    assertThat(repository.findById(second))
        .get()
        .satisfies(
            delivery -> {
              assertThat(delivery.getId()).isEqualTo(second);
              assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.HUB_TRANSIT);
              assertThat(delivery.getRecipientName()).isEqualTo("홍길동");
            });
    assertThat(repository.findById(deleted)).isEmpty();
    assertThat(repository.findById(UUID.randomUUID())).isEmpty();
  }

  @Test
  void findRoutesByDeliveryIdReturnsOnlyThatDeliverysRoutesInSequenceOrder() {
    UUID secondRoute = insertRoute(second, 2, HUB_B, HUB_C, DeliveryRouteStatus.WAITING, null);
    UUID firstRoute =
        insertRoute(second, 1, HUB_A, HUB_B, DeliveryRouteStatus.IN_TRANSIT, MANAGER_B);
    insertRoute(first, 1, HUB_A, HUB_B, DeliveryRouteStatus.WAITING, null);

    List<DeliveryRoute> routes = repository.findRoutesByDeliveryId(second);

    assertThat(routes).extracting(DeliveryRoute::getId).containsExactly(firstRoute, secondRoute);
    DeliveryRoute route = routes.getFirst();
    assertThat(route.getSequence()).isEqualTo(1);
    assertThat(route.getDepartureHubId()).isEqualTo(HUB_A);
    assertThat(route.getArrivalHubId()).isEqualTo(HUB_B);
    assertThat(route.getExpectedDistanceKm()).isEqualByComparingTo(new BigDecimal("12.345"));
    assertThat(route.getExpectedDurationSeconds()).isEqualTo(3600L);
    assertThat(route.getStatus()).isEqualTo(DeliveryRouteStatus.IN_TRANSIT);
    assertThat(route.getAssigneeId()).isEqualTo(MANAGER_B);
  }

  @Test
  void findRoutesByDeliveryIdsReturnsTheRoutesOfAllGivenDeliveriesInSequenceOrder() {
    UUID secondOfSecond = insertRoute(second, 2, HUB_B, HUB_C, DeliveryRouteStatus.WAITING, null);
    UUID firstOfFirst = insertRoute(first, 1, HUB_A, HUB_B, DeliveryRouteStatus.WAITING, null);
    UUID firstOfSecond = insertRoute(second, 1, HUB_A, HUB_B, DeliveryRouteStatus.IN_TRANSIT, null);
    insertRoute(third, 1, HUB_B, HUB_C, DeliveryRouteStatus.ARRIVED, null);
    insertRoute(deleted, 1, HUB_A, HUB_B, DeliveryRouteStatus.WAITING, null);

    List<DeliveryRoute> routes =
        repository.findRoutesByDeliveryIds(List.of(first, second, deleted));

    assertThat(routes)
        .extracting(DeliveryRoute::getId)
        .containsExactlyInAnyOrder(firstOfFirst, firstOfSecond, secondOfSecond);
    assertThat(routes)
        .filteredOn(route -> route.getDelivery().getId().equals(second))
        .extracting(DeliveryRoute::getId)
        .containsExactly(firstOfSecond, secondOfSecond);
  }

  @Test
  void migrationRejectsTheSameSequenceInOneDeliveryLikeTheEntityMapping() {
    insertRoute(second, 1, HUB_A, HUB_B, DeliveryRouteStatus.WAITING, null);
    insertRoute(first, 1, HUB_A, HUB_B, DeliveryRouteStatus.WAITING, null);

    assertThatThrownBy(
            () -> insertRoute(second, 1, HUB_B, HUB_C, DeliveryRouteStatus.WAITING, null))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void deletedRoutesAreExcludedFromBothRouteLookups() {
    UUID active = insertRoute(second, 1, HUB_A, HUB_B, DeliveryRouteStatus.IN_TRANSIT, null);
    UUID removed = insertRoute(second, 2, HUB_B, HUB_C, DeliveryRouteStatus.WAITING, null);
    jdbc.update(
        "UPDATE p_delivery_routes SET deleted_at = ?, deleted_by = 'deleter' WHERE delivery_route_id = ?",
        Timestamp.from(BASE.plusSeconds(5)),
        removed);

    assertThat(repository.findRoutesByDeliveryId(second))
        .extracting(DeliveryRoute::getId)
        .containsExactly(active);
    assertThat(repository.findRoutesByDeliveryIds(List.of(second)))
        .extracting(DeliveryRoute::getId)
        .containsExactly(active);
  }

  @Test
  void findRoutesByDeliveryIdIsEmptyForADeliveryWithoutRoutesOrADeletedOne() {
    insertRoute(deleted, 1, HUB_A, HUB_B, DeliveryRouteStatus.WAITING, null);

    assertThat(repository.findRoutesByDeliveryId(third)).isEmpty();
    assertThat(repository.findRoutesByDeliveryId(deleted)).isEmpty();
    assertThat(repository.findRoutesByDeliveryId(UUID.randomUUID())).isEmpty();
  }

  private List<UUID> ids(DeliverySearchCondition condition) {
    return repository.search(condition, page(0, 20)).map(Delivery::getId).getContent();
  }

  private static PageRequest page(int page, int size) {
    return PageRequest.of(page, size, NEWEST_FIRST);
  }

  private static DeliverySearchCondition condition(
      UUID orderId,
      DeliveryStatus status,
      UUID departureHubId,
      UUID destinationHubId,
      String address,
      String recipientName,
      UUID assigneeId) {
    return new DeliverySearchCondition(
        orderId, status, departureHubId, destinationHubId, address, recipientName, assigneeId);
  }

  private UUID insert(
      UUID departureHubId,
      UUID destinationHubId,
      String address,
      String recipientName,
      UUID assigneeId,
      DeliveryStatus status,
      int createdOffsetSeconds) {
    UUID id = UUID.randomUUID();
    Timestamp createdAt = Timestamp.from(BASE.plusSeconds(createdOffsetSeconds));
    jdbc.update(
        """
        INSERT INTO p_deliveries (
            delivery_id, order_id, status, departure_hub_id, destination_hub_id, address,
            recipient_name, delivery_manager_id, created_at, created_by, updated_at, updated_by)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'tester', ?, 'tester')
        """,
        id,
        UUID.randomUUID(),
        status.name(),
        departureHubId,
        destinationHubId,
        address,
        recipientName,
        assigneeId,
        createdAt,
        createdAt);
    return id;
  }

  private UUID insertRoute(
      UUID deliveryId,
      int sequence,
      UUID departureHubId,
      UUID arrivalHubId,
      DeliveryRouteStatus status,
      UUID assigneeId) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO p_delivery_routes (
            delivery_route_id, delivery_id, sequence, departure_hub_id, arrival_hub_id,
            expected_distance_km, expected_duration_seconds, status, assignee_id,
            created_at, created_by, updated_at, updated_by)
        VALUES (?, ?, ?, ?, ?, 12.345, 3600, ?, ?, ?, 'tester', ?, 'tester')
        """,
        id,
        deliveryId,
        sequence,
        departureHubId,
        arrivalHubId,
        status.name(),
        assigneeId,
        Timestamp.from(BASE),
        Timestamp.from(BASE));
    return id;
  }
}
