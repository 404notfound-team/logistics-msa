package com.notfound.logistics.hub.infrastructure.persistence.hubroute;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class HubRouteSeedIntegrationTest extends HubRouteDatabaseTestSupport {
  @Autowired private JdbcTemplate jdbc;
  @Autowired private Environment environment;

  private long count(String sql) {
    return jdbc.queryForObject(sql, Long.class);
  }

  @Test
  void localProfileLoadsAllRoutesWithValidReferencesAndAuditMetadata() {
    assertThat(count("SELECT COUNT(*) FROM hub.p_hubs")).isEqualTo(17);
    assertThat(count("SELECT COUNT(*) FROM hub.p_hub_routes")).isEqualTo(289);
    assertThat(
            count(
                "SELECT COUNT(*) FROM hub.p_hub_routes WHERE departure_hub_id = arrival_hub_id AND distance_km = 0 AND duration_minutes = 0"))
        .isEqualTo(17);
    assertThat(
            count(
                "SELECT COUNT(*) FROM hub.p_hub_routes WHERE distance_km < 0 OR duration_minutes < 0"))
        .isZero();
    assertThat(
            count(
                "SELECT COUNT(*) FROM hub.p_hub_routes r LEFT JOIN hub.p_hubs d ON d.id = r.departure_hub_id LEFT JOIN hub.p_hubs a ON a.id = r.arrival_hub_id WHERE d.id IS NULL OR a.id IS NULL"))
        .isZero();
    assertThat(
            count(
                "SELECT COUNT(*) FROM hub.p_hub_routes WHERE created_at IS NULL OR updated_at IS NULL OR created_by <> 'system' OR updated_by <> 'system' OR deleted_at IS NOT NULL OR deleted_by IS NOT NULL"))
        .isZero();
    assertThat(
            count(
                "SELECT COUNT(*) FROM (SELECT departure_hub_id, arrival_hub_id FROM hub.p_hub_routes GROUP BY departure_hub_id, arrival_hub_id HAVING COUNT(*) > 1) duplicates"))
        .isZero();
  }

  @Test
  void runningConfiguredSeedsTwicePreservesIdsValuesAndAuditTimes() throws Exception {
    List<Map<String, Object>> before =
        jdbc.queryForList("SELECT * FROM hub.p_hub_routes ORDER BY id");
    List<Map<String, Object>> hubsBefore =
        jdbc.queryForList("SELECT * FROM hub.p_hubs ORDER BY id");
    var loader = new DefaultResourceLoader();
    var populator = new ResourceDatabasePopulator();
    populator.setSqlScriptEncoding("UTF-8");
    for (int i = 0; i < 2; i++) {
      // Read actual configuration so a missing or renamed file fails this test.
      for (int index = 0; index < 2; index++) {
        String location =
            environment.getRequiredProperty("spring.sql.init.data-locations[" + index + "]");
        populator.addScript(loader.getResource(location));
      }
    }
    jdbc.execute(
        (org.springframework.jdbc.core.ConnectionCallback<Void>)
            connection -> {
              populator.populate(connection);
              return null;
            });
    assertThat(jdbc.queryForList("SELECT * FROM hub.p_hub_routes ORDER BY id")).isEqualTo(before);
    assertThat(jdbc.queryForList("SELECT * FROM hub.p_hubs ORDER BY id")).isEqualTo(hubsBefore);
  }
}
