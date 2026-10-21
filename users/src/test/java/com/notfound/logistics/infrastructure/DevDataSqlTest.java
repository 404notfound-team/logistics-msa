package com.notfound.logistics.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

/** 업체 샘플 SQL의 반복 적재·제거와 롤백을 검증한다. */
@Testcontainers
class DevDataSqlTest {
  private static final Path ROOT = Path.of(System.getProperty("logistics.repo-root"));
  private static final String FIRST_ID = "c0ffee39-0000-4000-8000-000000000001";
  private static final String NORMAL_ID = "11111111-1111-4111-8111-111111111111";
  private static final String MANAGER_ID = "22222222-2222-4222-8222-222222222222";
  private static final String OTHER_MANAGER_ID = "33333333-3333-4333-8333-333333333333";

  @Container
  static final PostgreSQLContainer<?> DATABASE =
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
              MountableFile.forHostPath(ROOT.resolve("infra/postgres/01-init.sh").toString(), 0755),
              "/docker-entrypoint-initdb.d/01-init.sh")
          .withCopyFileToContainer(
              MountableFile.forHostPath(
                  ROOT.resolve("company/src/main/resources/db/seed/local-companies.sql")
                      .toString()),
              "/dev-data/companies.sql")
          .withCopyFileToContainer(
              MountableFile.forHostPath(
                  ROOT.resolve("infra/dev-data/clear-companies.sql").toString()),
              "/dev-data/clear-companies.sql");

  @BeforeEach
  void prepareCompanyTable() throws Exception {
    execute(
        "DROP TABLE IF EXISTS company.p_products; DROP TABLE IF EXISTS company.p_companies;"
            + " CREATE TABLE company.p_companies ("
            + "company_id uuid PRIMARY KEY, name varchar(100) NOT NULL, hub_id uuid NOT NULL,"
            + " address varchar(255) NOT NULL, manager_user_id uuid NOT NULL,"
            + " created_at timestamptz NOT NULL, created_by varchar(100) NOT NULL,"
            + " updated_at timestamptz NOT NULL, updated_by varchar(100) NOT NULL,"
            + " deleted_at timestamptz, deleted_by varchar(100));"
            + " CREATE TABLE company.p_products (product_id uuid PRIMARY KEY,"
            + " company_id uuid NOT NULL REFERENCES company.p_companies, name varchar(100) NOT NULL,"
            + " description varchar(1000), price bigint NOT NULL,"
            + " created_at timestamptz NOT NULL, created_by varchar(100) NOT NULL,"
            + " updated_at timestamptz NOT NULL, updated_by varchar(100) NOT NULL,"
            + " deleted_at timestamptz, deleted_by varchar(100))");
  }

  @Test
  void repeatedApplyKeepsStableIdsAndExistingEdits() throws Exception {
    assertSuccess(seed(true, MANAGER_ID));
    execute("UPDATE company.p_companies SET name = 'edited' WHERE company_id = '" + FIRST_ID + "'");
    assertSuccess(seed(true, OTHER_MANAGER_ID));

    assertThat(query("SELECT count(*) FROM company.p_companies")).isEqualTo("34");
    assertThat(query("SELECT count(*) FROM company.p_products")).isEqualTo("102");
    assertThat(query("SELECT count(DISTINCT hub_id) FROM company.p_companies")).isEqualTo("17");
    assertThat(
            query(
                "SELECT count(*) FROM company.p_companies WHERE manager_user_id = '"
                    + MANAGER_ID
                    + "'"))
        .isEqualTo("34");
    assertThat(query("SELECT name FROM company.p_companies WHERE company_id = '" + FIRST_ID + "'"))
        .isEqualTo("edited");
    assertThat(
            query(
                "SELECT count(*) FROM company.p_companies WHERE created_by = 'dev-seed'"
                    + " AND updated_by = 'dev-seed' AND created_at = updated_at"
                    + " AND deleted_at IS NULL"))
        .isEqualTo("34");
  }

  @Test
  void clearOnlyDeletesReservedIdsWithTheSeedActor() throws Exception {
    assertSuccess(seed(true, MANAGER_ID));
    insertCompany(NORMAL_ID, "dev-seed");
    execute(
        "UPDATE company.p_companies SET created_by = 'human' WHERE company_id = '"
            + FIRST_ID
            + "'");

    assertSuccess(seed(false, ""));
    assertSuccess(seed(false, ""));

    assertThat(query("SELECT count(*) FROM company.p_companies")).isEqualTo("2");
    assertThat(
            query(
                "SELECT created_by FROM company.p_companies WHERE company_id = '" + FIRST_ID + "'"))
        .isEqualTo("human");
    assertThat(
            query(
                "SELECT created_by FROM company.p_companies WHERE company_id = '"
                    + NORMAL_ID
                    + "'"))
        .isEqualTo("dev-seed");
  }

  @Test
  void cleanAndApplyRebuildTheSampleSet() throws Exception {
    assertSuccess(seed(true, MANAGER_ID));
    assertSuccess(seed(false, ""));
    assertThat(query("SELECT count(*) FROM company.p_companies")).isEqualTo("0");
    assertSuccess(seed(true, OTHER_MANAGER_ID));
    assertThat(
            query(
                "SELECT count(*) FROM company.p_companies WHERE manager_user_id = '"
                    + OTHER_MANAGER_ID
                    + "'"))
        .isEqualTo("34");
  }

  @Test
  void aReservedIdOwnedBySomeoneElseAbortsTheWholeApply() throws Exception {
    insertCompany(FIRST_ID, "human");

    ExecResult result = seed(true, MANAGER_ID);

    assertThat(result.getExitCode()).isNotZero();
    assertThat(result.getStderr()).contains("belongs to another actor");
    assertThat(query("SELECT count(*) FROM company.p_companies")).isEqualTo("1");
    assertThat(query("SELECT created_by FROM company.p_companies")).isEqualTo("human");
  }

  @ParameterizedTest
  @ValueSource(strings = {"42", "42'); DROP TABLE company.p_companies; --"})
  void invalidManagerInputCannotExecuteSqlAndDoesNotLeavePartialRows(String managerId)
      throws Exception {
    insertCompany(NORMAL_ID, "human");

    ExecResult result = seed(true, managerId);

    assertThat(result.getExitCode()).isNotZero();
    assertThat(query("SELECT count(*) FROM company.p_companies")).isEqualTo("1");
    assertThat(query("SELECT created_by FROM company.p_companies")).isEqualTo("human");
  }

  @Test
  void missingDomainTableFailsWithoutCreatingIt() throws Exception {
    execute("DROP TABLE company.p_products; DROP TABLE company.p_companies");

    ExecResult result = seed(true, MANAGER_ID);

    assertThat(result.getExitCode()).isNotZero();
    assertThat(result.getStderr()).contains("does not exist");
    assertThat(query("SELECT to_regclass('company.p_companies') IS NULL")).isEqualTo("t");
  }

  @Test
  void foreignProductRollsBackTheWholeCleanup() throws Exception {
    assertSuccess(seed(true, MANAGER_ID));
    execute(
        "INSERT INTO company.p_products (product_id, company_id, name, price, created_at, created_by, updated_at, updated_by)"
            + " VALUES ('"
            + NORMAL_ID
            + "', '"
            + FIRST_ID
            + "', 'human product', 1, now(), 'human', now(), 'human')");
    assertThat(seed(false, "").getExitCode()).isNotZero();
    assertThat(query("SELECT count(*) FROM company.p_companies")).isEqualTo("34");
    assertThat(query("SELECT count(*) FROM company.p_products")).isEqualTo("103");
  }

  @Test
  void repeatPreservesLogicalDeletionAndDoesNotRecreateItsProducts() throws Exception {
    assertSuccess(seed(true, MANAGER_ID));
    execute(
        "DELETE FROM company.p_products WHERE company_id = '"
            + FIRST_ID
            + "';"
            + " UPDATE company.p_companies SET deleted_at = now(), deleted_by = 'human' WHERE company_id = '"
            + FIRST_ID
            + "'");
    assertSuccess(seed(true, MANAGER_ID));
    assertThat(query("SELECT count(*) FROM company.p_products")).isEqualTo("99");
    assertThat(
            query(
                "SELECT deleted_by FROM company.p_companies WHERE company_id = '" + FIRST_ID + "'"))
        .isEqualTo("human");
  }

  private static ExecResult seed(boolean apply, String managerId) throws Exception {
    return DATABASE.execInContainer(
        "env",
        "PGPASSWORD=company-test-only",
        "PGOPTIONS=-c logistics.dev_manager_user_id=" + (apply ? managerId : MANAGER_ID),
        "psql",
        "-X",
        "--host=127.0.0.1",
        "--username=company_app",
        "--dbname=company_db",
        "--set=ON_ERROR_STOP=1",
        "--set=apply=" + apply,
        "--set=manager_user_id=" + managerId,
        "--file=/dev-data/" + (apply ? "companies.sql" : "clear-companies.sql"));
  }

  private static void insertCompany(String id, String actor) throws Exception {
    execute(
        "INSERT INTO company.p_companies VALUES ('"
            + id
            + "', 'regular',"
            + " '00000000-0000-0000-0000-000000000001', 'regular address', '"
            + MANAGER_ID
            + "',"
            + " now(), '"
            + actor
            + "', now(), '"
            + actor
            + "', null, null)");
  }

  private static void execute(String sql) throws Exception {
    assertSuccess(command(sql));
  }

  private static String query(String sql) throws Exception {
    ExecResult result = command(sql);
    assertSuccess(result);
    return result.getStdout().trim();
  }

  private static ExecResult command(String sql) throws Exception {
    return DATABASE.execInContainer(
        "env",
        "PGPASSWORD=company-test-only",
        "psql",
        "-X",
        "--host=127.0.0.1",
        "--username=company_app",
        "--dbname=company_db",
        "--set=ON_ERROR_STOP=1",
        "--tuples-only",
        "--no-align",
        "--command=" + sql);
  }

  private static void assertSuccess(ExecResult result) {
    assertThat(result.getExitCode()).withFailMessage(result.getStderr()).isZero();
  }
}
