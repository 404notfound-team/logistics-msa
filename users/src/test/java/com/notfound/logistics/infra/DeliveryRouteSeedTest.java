package com.notfound.logistics.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

/** 실제 Hub DB에서 내보낸 경로를 Delivery DB에 적재하는 로컬 도구의 계산·보존·실패 원자성을 검증한다. */
@Testcontainers
class DeliveryRouteSeedTest {
  private static final Path ROOT = Path.of(System.getProperty("logistics.repo-root"));
  private static final String FIRST_ROUTE = "c0ffee39-0007-4000-8000-000000000011";
  private static final String FIRST_DELIVERY = "c0ffee39-0004-4000-8000-000000000001";
  private static final String SQL_ROOT = "delivery/src/main/resources/db/";

  @Container static final PostgreSQLContainer<?> DATABASE = database();

  private static PostgreSQLContainer<?> database() {
    var database =
        new PostgreSQLContainer<>("postgres:18")
            .withDatabaseName("postgres")
            .withUsername("postgres")
            .withPassword("admin-test-only")
            .withEnv("USERS_DB_PASSWORD", "users-test-only")
            .withEnv("COMPANY_DB_PASSWORD", "company-test-only")
            .withEnv("HUB_DB_PASSWORD", "hub-test-only")
            .withEnv("ORDERS_DB_PASSWORD", "orders-test-only")
            .withEnv("DELIVERY_DB_PASSWORD", "delivery-test-only")
            .withCopyFileToContainer(
                MountableFile.forHostPath(ROOT.resolve("infra/postgres/01-init.sh")),
                "/docker-entrypoint-initdb.d/01-init.sh");
    for (String path :
        new String[] {
          "infra/dev-data/seed-delivery-routes.sh",
          "hub/src/main/resources/db/seed/hub-routes.sql",
          SQL_ROOT + "migration/V1__create_p_deliveries.sql",
          SQL_ROOT + "migration/V2__create_p_delivery_routes.sql",
          SQL_ROOT + "seed/local_p_deliveries.sql",
          SQL_ROOT + "seed/local_p_delivery_routes.sql"
        }) {
      database.withCopyFileToContainer(
          MountableFile.forHostPath(ROOT.resolve(path)), "/workspace/" + path);
    }
    return database;
  }

  @BeforeEach
  void prepareActualSeedsAndDeliverySchema() throws Exception {
    query(
        "hub",
        "DROP TABLE IF EXISTS hub.p_hub_routes;"
            + " CREATE TABLE hub.p_hub_routes (id uuid PRIMARY KEY,"
            + " departure_hub_id uuid NOT NULL, arrival_hub_id uuid NOT NULL,"
            + " distance_km numeric(12,2) NOT NULL, duration_minutes integer NOT NULL,"
            + " created_at timestamptz, created_by text, updated_at timestamptz, updated_by text,"
            + " deleted_at timestamptz, deleted_by text, UNIQUE(departure_hub_id, arrival_hub_id))");
    file("hub", "hub/src/main/resources/db/seed/hub-routes.sql");
    query("delivery", "DROP TABLE IF EXISTS delivery.p_delivery_routes, delivery.p_deliveries");
    file("delivery", SQL_ROOT + "migration/V1__create_p_deliveries.sql");
    file("delivery", SQL_ROOT + "migration/V2__create_p_delivery_routes.sql");
    file("delivery", SQL_ROOT + "seed/local_p_deliveries.sql");
  }

  @Test
  void connectsOneAndTwoLegJourneysWithConsistentStatesAndDeletedParents() throws Exception {
    success(seed());
    assertThat(query("delivery", "SELECT count(*) FROM delivery.p_delivery_routes"))
        .isEqualTo("33");
    assertThat(
            query("delivery", "SELECT count(DISTINCT delivery_id) FROM delivery.p_delivery_routes"))
        .isEqualTo("26");
    assertThat(
            query(
                "delivery",
                "SELECT count(*) FROM delivery.p_delivery_routes WHERE deleted_at IS NULL"))
        .isEqualTo("25");
    assertThat(
            query(
                "delivery",
                "SELECT count(*) FROM delivery.p_delivery_routes WHERE status = 'CANCELLED' AND deleted_at IS NOT NULL"))
        .isEqualTo("7");
    assertThat(
            query(
                "delivery",
                "SELECT count(*) FROM delivery.p_delivery_routes WHERE status = 'IN_TRANSIT'"))
        .isEqualTo("4");
    assertThat(
            query(
                "delivery",
                "SELECT count(*) FROM delivery.p_delivery_routes WHERE status = 'ARRIVED' AND actual_distance_km = expected_distance_km AND actual_duration_seconds = expected_duration_seconds"))
        .isEqualTo("16");
    assertThat(
            query(
                "delivery",
                "SELECT count(*) FROM delivery.p_delivery_routes WHERE departure_hub_id = arrival_hub_id AND expected_distance_km = 0 AND expected_duration_seconds = 0"))
        .isEqualTo("12");
    assertThat(
            query(
                "delivery",
                "SELECT count(*) FROM delivery.p_delivery_routes a JOIN delivery.p_delivery_routes b ON a.delivery_id = b.delivery_id AND a.sequence = 1 AND b.sequence = 2 AND a.arrival_hub_id = b.departure_hub_id"))
        .isEqualTo("7");
    assertThat(
            query(
                "delivery",
                "SELECT count(*) FROM delivery.p_delivery_routes WHERE status <> 'ARRIVED' AND (actual_distance_km IS NOT NULL OR actual_duration_seconds IS NOT NULL)"))
        .isEqualTo("0");
  }

  @Test
  void readsCurrentHubValuesAndConvertsMinutesWithoutIntegerOverflow() throws Exception {
    query(
        "hub",
        "UPDATE hub.p_hub_routes SET distance_km = 456.78, duration_minutes = 60000000 WHERE departure_hub_id = '00000000-0000-0000-0000-000000000001' AND arrival_hub_id = '00000000-0000-0000-0000-000000000003'");
    success(seed());
    assertThat(
            query(
                "delivery",
                "SELECT expected_distance_km || ',' || expected_duration_seconds FROM delivery.p_delivery_routes WHERE delivery_route_id = '"
                    + FIRST_ROUTE
                    + "'"))
        .isEqualTo("456.780,3600000000");
  }

  @Test
  void repeatPreservesEveryExistingValueIncludingEditsAndLogicalDeletion() throws Exception {
    success(seed());
    query(
        "delivery",
        "UPDATE delivery.p_delivery_routes SET status = 'CANCELLED', expected_distance_km = 1.234, assignee_id = NULL, deleted_at = now(), deleted_by = 'human' WHERE delivery_route_id = '"
            + FIRST_ROUTE
            + "'");
    String before = snapshot();
    success(seed());
    success(seed());
    assertThat(snapshot()).isEqualTo(before);
  }

  @Test
  void missingHubLegFailsWithoutPartiallyInsertingRoutes() throws Exception {
    query(
        "hub",
        "DELETE FROM hub.p_hub_routes WHERE departure_hub_id = '00000000-0000-0000-0000-000000000001' AND arrival_hub_id = '00000000-0000-0000-0000-000000000009'");
    ExecResult result = seed();
    assertThat(result.getExitCode()).isNotZero();
    assertThat(result.getStderr()).contains("Missing Hub route");
    assertThat(query("delivery", "SELECT count(*) FROM delivery.p_delivery_routes")).isEqualTo("0");
  }

  @Test
  void reservedIdOwnedByAnotherActorIsNeverOverwritten() throws Exception {
    insertForeignRoute(FIRST_ROUTE, "11111111-1111-4111-8111-111111111111");
    assertForeignCollision();
  }

  @Test
  void existingDeliverySequenceOwnedByAnotherActorIsNeverSkippedSilently() throws Exception {
    insertForeignRoute("11111111-1111-4111-8111-111111111111", FIRST_DELIVERY);
    assertForeignCollision();
  }

  @Test
  void failedHubReadDoesNotWriteDeliveryRows() throws Exception {
    admin("REVOKE CONNECT ON DATABASE hub_db FROM hub_app");
    try {
      ExecResult result = seed();
      assertThat(result.getExitCode()).isNotZero();
      assertThat(query("delivery", "SELECT count(*) FROM delivery.p_delivery_routes"))
          .isEqualTo("0");
    } finally {
      admin("GRANT CONNECT ON DATABASE hub_db TO hub_app");
    }
  }

  @Test
  void missingParentDeliveriesDoesNotReportSuccessfulEmptySeeding() throws Exception {
    query("delivery", "DELETE FROM delivery.p_deliveries");
    ExecResult result = seed();
    assertThat(result.getExitCode()).isNotZero();
    assertThat(result.getStderr()).contains("Development deliveries must be loaded");
    assertThat(query("delivery", "SELECT count(*) FROM delivery.p_delivery_routes")).isEqualTo("0");
  }

  private void assertForeignCollision() throws Exception {
    String before = snapshot();
    ExecResult result = seed();
    assertThat(result.getExitCode()).isNotZero();
    assertThat(result.getStderr()).contains("belongs to another actor");
    assertThat(snapshot()).isEqualTo(before);
  }

  private void insertForeignRoute(String id, String deliveryId) throws Exception {
    query(
        "delivery",
        "INSERT INTO delivery.p_delivery_routes (delivery_route_id, delivery_id, sequence, departure_hub_id, arrival_hub_id, expected_distance_km, expected_duration_seconds, status, created_at, created_by, updated_at, updated_by) VALUES ('"
            + id
            + "', '"
            + deliveryId
            + "', 1, '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000002', 1, 60, 'WAITING', now(), 'human', now(), 'human')");
  }

  private String snapshot() throws Exception {
    return query(
        "delivery",
        "SELECT jsonb_agg(to_jsonb(r) ORDER BY delivery_route_id) FROM delivery.p_delivery_routes r");
  }

  private static ExecResult seed() throws Exception {
    return DATABASE.execInContainer(
        "env", "PGHOST=127.0.0.1", "bash", "/workspace/infra/dev-data/seed-delivery-routes.sh");
  }

  private static String query(String service, String sql) throws Exception {
    ExecResult result = command(service, "--command=" + sql);
    success(result);
    return result.getStdout().trim();
  }

  private static void file(String service, String path) throws Exception {
    success(command(service, "--file=/workspace/" + path));
  }

  private static ExecResult command(String service, String argument) throws Exception {
    return DATABASE.execInContainer(
        "env",
        "PGPASSWORD=" + service + "-test-only",
        "PGOPTIONS=-c search_path=" + service,
        "psql",
        "-X",
        "--host=127.0.0.1",
        "--username=" + service + "_app",
        "--dbname=" + service + "_db",
        "--set=ON_ERROR_STOP=1",
        "--tuples-only",
        "--no-align",
        argument);
  }

  private static void success(ExecResult result) {
    assertThat(result.getExitCode()).withFailMessage(result.getStderr()).isZero();
  }

  private static void admin(String sql) throws Exception {
    success(
        DATABASE.execInContainer(
            "psql", "-U", "postgres", "-d", "postgres", "-v", "ON_ERROR_STOP=1", "-c", sql));
  }
}
