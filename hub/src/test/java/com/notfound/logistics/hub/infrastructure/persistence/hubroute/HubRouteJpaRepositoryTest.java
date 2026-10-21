package com.notfound.logistics.hub.infrastructure.persistence.hubroute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.notfound.logistics.hub.application.service.hubroute.HubRouteQueryService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class HubRouteJpaRepositoryTest extends HubRouteDatabaseTestSupport {
  @Autowired private HubRouteQueryService service;
  @Autowired private HubRouteJpaRepository repository;
  @Autowired private JdbcTemplate jdbc;

  private UUID hub(int number) {
    return UUID.fromString("00000000-0000-0000-0000-" + String.format("%012d", number));
  }

  @Test
  void oversizedPagesReturnEmptyContentWithActualDatabaseCount() {
    var all = service.getRoutes(null, null, Integer.MAX_VALUE, 100);
    assertThat(all.getContent()).isEmpty();
    assertThat(all.getTotalElements()).isEqualTo(289);
    var filtered = service.getRoutes(hub(1), hub(2), Integer.MAX_VALUE, 1);
    assertThat(filtered.getContent()).isEmpty();
    assertThat(filtered.getTotalElements()).isEqualTo(1);
  }

  @Test
  void searchesEachFilterWithMatchingTotals() {
    var page = PageRequest.of(0, 5);
    assertThat(repository.findAll(page).getTotalElements()).isEqualTo(289);
    assertThat(repository.findByDepartureHubId(hub(1), page).getTotalElements()).isEqualTo(17);
    assertThat(repository.findByArrivalHubId(hub(2), page).getTotalElements()).isEqualTo(17);
    assertThat(
            repository.findByDepartureHubIdAndArrivalHubId(hub(1), hub(2), page).getTotalElements())
        .isEqualTo(1);
    assertThat(repository.findByDepartureHubId(UUID.randomUUID(), page).getContent()).isEmpty();
  }

  @ParameterizedTest
  @CsvSource({"false,false", "true,false", "false,true", "true,true"})
  void serviceOrdersByCreationDescendingThenIdAcrossPages(boolean departure, boolean arrival) {
    jdbc.update("DELETE FROM hub.p_hub_routes");
    insertRoute(1, 1, 1, "2026-01-03T00:00:00Z");
    insertRoute(2, 1, 2, "2026-01-01T00:00:00Z");
    insertRoute(3, 1, 3, "2026-01-02T00:00:00Z");
    insertRoute(4, 2, 1, "2026-01-02T00:00:00Z");
    insertRoute(5, 3, 1, "2026-01-01T12:00:00Z");
    insertRoute(6, 4, 4, "2025-12-31T00:00:00Z");
    List<UUID> expected =
        departure && arrival
            ? List.of(hub(1))
            : departure
                ? List.of(hub(1), hub(3), hub(2))
                : arrival
                    ? List.of(hub(1), hub(4), hub(5))
                    : List.of(hub(1), hub(4), hub(3), hub(5), hub(2), hub(6));
    UUID from = departure ? hub(1) : null;
    UUID to = arrival ? hub(1) : null;
    assertThat(service.getRoutes(from, to, 0, 100).getContent())
        .extracting(result -> result.id())
        .containsExactlyElementsOf(expected);
    List<UUID> paged = new ArrayList<>();
    for (int page = 0; page < expected.size(); page++) {
      var result = service.getRoutes(from, to, page, 1);
      assertThat(result.getTotalElements()).isEqualTo(expected.size());
      paged.add(result.getContent().getFirst().id());
    }
    assertThat(paged).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
    assertThat(service.getRoutes(from, to, expected.size(), 1).getContent()).isEmpty();
  }

  private void insertRoute(int id, int departure, int arrival, String createdAt) {
    jdbc.update(
        "INSERT INTO hub.p_hub_routes (id, departure_hub_id, arrival_hub_id, duration_minutes, distance_km, created_at, created_by, updated_at, updated_by) VALUES (?, ?, ?, 0, 0, ?, 'test', ?, 'test')",
        hub(id),
        hub(departure),
        hub(arrival),
        java.sql.Timestamp.from(Instant.parse(createdAt)),
        java.sql.Timestamp.from(Instant.parse(createdAt)));
  }

  @Test
  void requestedSortUsesMappedFieldsAndStableTieBreakers() {
    jdbc.update("DELETE FROM hub.p_hub_routes");
    insertRoute(1, 1, 2, "2026-01-01T00:00:00Z");
    insertRoute(2, 1, 3, "2026-01-01T00:00:00Z");
    insertRoute(3, 1, 4, "2026-01-02T00:00:00Z");
    assertThat(service.getRoutes(hub(1), null, 0, 20, "created_at,asc").getContent())
        .extracting(result -> result.id())
        .containsExactly(hub(2), hub(1), hub(3));
    assertThat(service.getRoutes(hub(1), null, 0, 20, "id,asc").getContent())
        .extracting(result -> result.id())
        .containsExactly(hub(1), hub(2), hub(3));
  }

  @Test
  void excludesDeletedRoutesFromAllSearchesAndCounts() {
    jdbc.update(
        "UPDATE hub.p_hub_routes SET deleted_at = ?, deleted_by = 'test' WHERE departure_hub_id = ? AND arrival_hub_id = ?",
        java.sql.Timestamp.from(Instant.now()),
        hub(1),
        hub(2));
    var page = PageRequest.of(0, 100);
    assertThat(repository.count()).isEqualTo(288);
    assertThat(repository.countByDepartureHubId(hub(1))).isEqualTo(16);
    assertThat(repository.countByArrivalHubId(hub(2))).isEqualTo(16);
    assertThat(repository.countByDepartureHubIdAndArrivalHubId(hub(1), hub(2))).isZero();
    assertThat(repository.findAll(page).getTotalElements()).isEqualTo(288);
    assertThat(repository.findByDepartureHubId(hub(1), page).getTotalElements()).isEqualTo(16);
    assertThat(repository.findByArrivalHubId(hub(2), page).getTotalElements()).isEqualTo(16);
    assertThat(repository.findByDepartureHubIdAndArrivalHubId(hub(1), hub(2), page).getContent())
        .isEmpty();
  }

  @Test
  void rejectsDuplicateDepartureAndArrivalPair() {
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO hub.p_hub_routes (id, departure_hub_id, arrival_hub_id, duration_minutes, distance_km, created_at, created_by, updated_at, updated_by) VALUES (?, ?, ?, 1, 1, NOW(), 'test', NOW(), 'test')",
                    UUID.randomUUID(),
                    hub(1),
                    hub(2)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
