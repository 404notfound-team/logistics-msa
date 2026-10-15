package com.notfound.logistics.hub.infrastructure.persistence.hubroute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.notfound.logistics.hub.application.service.hubroute.HubRouteQueryService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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

  @Test
  void sortsAndPaginatesWithoutDuplicates() {
    var sort = Sort.by("departureHubId", "arrivalHubId");
    var first = repository.findAll(PageRequest.of(0, 1, sort));
    var second = repository.findAll(PageRequest.of(1, 1, sort));
    assertThat(first.getContent().getFirst().getDepartureHubId()).isEqualTo(hub(1));
    assertThat(first.getContent().getFirst().getArrivalHubId()).isEqualTo(hub(1));
    assertThat(second.getContent().getFirst().getArrivalHubId()).isEqualTo(hub(2));
    assertThat(repository.findAll(PageRequest.of(289, 1, sort)).getContent()).isEmpty();
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
