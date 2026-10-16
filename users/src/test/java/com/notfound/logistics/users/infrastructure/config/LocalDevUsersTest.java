package com.notfound.logistics.users.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

@Testcontainers
class LocalDevUsersTest {
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
              MountableFile.forHostPath(
                  Path.of(System.getProperty("logistics.repo-root"), "infra/postgres/01-init.sh")
                      .toString(),
                  0755),
              "/docker-entrypoint-initdb.d/01-init.sh");

  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
  private JdbcTemplate jdbc;
  private DriverManagerDataSource datasource;

  @BeforeEach
  void prepare() {
    datasource =
        new DriverManagerDataSource(
            "jdbc:postgresql://"
                + DATABASE.getHost()
                + ":"
                + DATABASE.getMappedPort(5432)
                + "/users_db?currentSchema=users",
            "users_app",
            "users-test-only");
    jdbc = new JdbcTemplate(datasource);
    jdbc.execute("DROP TABLE IF EXISTS users.p_users");
    jdbc.execute(
        """
        CREATE TABLE users.p_users (
          id uuid PRIMARY KEY, username varchar(10) NOT NULL UNIQUE, name varchar(100) NOT NULL,
          password_hash varchar(255) NOT NULL, role varchar(10) NOT NULL,
          slack_id varchar(100) NOT NULL, company_id uuid, hub_id uuid,
          is_delivery_staff boolean NOT NULL,
          created_at timestamptz NOT NULL, created_by varchar(100) NOT NULL,
          updated_at timestamptz NOT NULL, updated_by varchar(100) NOT NULL,
          deleted_at timestamptz, deleted_by varchar(100))
        """);
  }

  @Test
  void createsThreeLoginAccountsWithHashedPasswordAndConnectedIds() {
    seed("TestOnly1!pass").run(null);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM users.p_users", Integer.class))
        .isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "SELECT role FROM users.p_users WHERE username='devmaster'", String.class))
        .isEqualTo("MASTER");
    assertThat(
            jdbc.queryForObject(
                "SELECT id::text FROM users.p_users WHERE username='devseed'", String.class))
        .isEqualTo("c0ffee39-0000-4000-8000-000000000099");
    for (String hash : jdbc.queryForList("SELECT password_hash FROM users.p_users", String.class)) {
      assertThat(encoder.matches("TestOnly1!pass", hash)).isTrue();
    }
  }

  @Test
  void repeatPreservesExistingPasswordRoleAndLogicalDeletion() {
    seed("TestOnly1!pass").run(null);
    jdbc.update(
        "UPDATE users.p_users SET role='USER', deleted_at=now(), deleted_by='human'"
            + " WHERE username='devmaster'");
    var before = jdbc.queryForList("SELECT * FROM users.p_users ORDER BY username");
    seed("Different1!pass").run(null);
    assertThat(jdbc.queryForList("SELECT * FROM users.p_users ORDER BY username"))
        .isEqualTo(before);
  }

  @Test
  void occupiedUsernameAbortsWithoutAddingOtherAccounts() {
    seed("TestOnly1!pass").run(null);
    jdbc.update("DELETE FROM users.p_users WHERE username <> 'devseed2'");
    jdbc.update("UPDATE users.p_users SET created_by='human'");
    assertThatThrownBy(() -> seed("TestOnly1!pass").run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("belongs to another actor");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM users.p_users", Integer.class))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT created_by FROM users.p_users", String.class))
        .isEqualTo("human");
  }

  @Test
  void emptyPasswordDoesNotCreateAccounts() {
    assertThatThrownBy(() -> seed("").run(null)).isInstanceOf(IllegalStateException.class);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM users.p_users", Integer.class)).isZero();
  }

  private LocalDevUsers seed(String password) {
    return new LocalDevUsers(jdbc, new DataSourceTransactionManager(datasource), encoder, password);
  }
}
