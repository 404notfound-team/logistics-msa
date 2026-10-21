package com.notfound.logistics.hub.infrastructure.persistence.hubroute;

import static org.assertj.core.api.Assertions.assertThat;

import com.notfound.logistics.hub.application.service.hub.HubQueryService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class HubSearchIntegrationTest extends HubRouteDatabaseTestSupport {
  @Autowired private HubQueryService service;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void externalSearchFiltersContentAndCountAndPreservesInternalExactMatch() {
    var page = service.getHubs(0, 1, "desc", "경기", null);
    assertThat(page.getContent()).hasSize(1);
    assertThat(page.getTotalElements()).isEqualTo(2);
    assertThat(page.getContent().getFirst().name()).contains("경기");
    assertThat(service.getHubs(21474837, 100, "desc", "경기", null).getTotalElements()).isEqualTo(2);
    assertThat(service.getHubs(21474837, 100, "desc", "경기", null).getContent()).isEmpty();
    assertThat(service.getHubs(0, 20, "desc", "__NO_SUCH_HUB__", null).getTotalElements()).isZero();
    assertThat(service.getHubsByName("경기")).isEmpty();
    assertThat(service.getHubsByName("서울특별시 센터")).hasSize(1);
  }

  @Test
  void literalWildcardsCaseAndDeletedRowsUseTheSameCountFilter() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO hub.p_hubs (id,name,address,created_at,created_by,updated_at,updated_by) VALUES (?, ?, '주소',NOW(),'test',NOW(),'test')",
        id,
        "AbC_%\\센터");
    assertThat(service.getHubs(0, 20, "desc", "abc_%\\", null).getContent())
        .extracting(result -> result.id())
        .containsExactly(id);
    assertThat(service.getHubs(0, 20, "desc", "%", null).getTotalElements()).isEqualTo(1);
    assertThat(service.getHubs(0, 20, "desc", "_", null).getTotalElements()).isEqualTo(1);
    jdbc.update("UPDATE hub.p_hubs SET deleted_at=NOW(),deleted_by='test' WHERE id=?", id);
    var deleted = service.getHubs(0, 20, "desc", "abc", null);
    assertThat(deleted.getContent()).isEmpty();
    assertThat(deleted.getTotalElements()).isZero();
    assertThat(service.getHubsByName("AbC_%\\센터")).isEmpty();
  }

  @Test
  void explicitSortOverridesLegacyDirectionAndHasNoPageDuplicates() {
    var all = service.getHubs(0, 100, "desc", null, "id,asc");
    assertThat(all.getContent()).extracting(result -> result.id().toString()).isSorted();
    var first = service.getHubs(0, 1, "asc", null, "created_at,desc");
    var second = service.getHubs(1, 1, "asc", null, "created_at,desc");
    assertThat(first.getContent()).doesNotContainAnyElementsOf(second.getContent());
    assertThat(service.getHubs(0, 20, "desc", " ", null).getTotalElements()).isEqualTo(17);
  }
}
