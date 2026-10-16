package com.notfound.logistics.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.notfound.logistics.users.UsersApplication;
import com.notfound.logistics.users.application.service.UserService;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

@ActiveProfiles("local")
@SpringBootTest(
    classes = {UsersApplication.class, DevSchemaInitializationTest.SchemaConfiguration.class},
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "eureka.client.enabled=false",
      "management.tracing.enabled=false",
      "springdoc.api-docs.enabled=false",
      "spring.data.jpa.repositories.enabled=false",
      "users.audit.enabled=false",
      "users.jwt.secret=test-only-signing-key-for-users-module-tests-32bytes",
      "USERS_SERVICE_TOKEN=test-only-users-service-token",
      "GATEWAY_SERVICE_TOKEN=test-only-gateway-service-token"
    })
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DevSchemaInitializationTest {
  @MockitoBean private UserService userService;

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

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () ->
            "jdbc:postgresql://"
                + DATABASE.getHost()
                + ":"
                + DATABASE.getMappedPort(5432)
                + "/users_db?currentSchema=users");
    registry.add("spring.datasource.password", () -> "users-test-only");
  }

  @Autowired private EntityManagerFactory entities;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void localProfileCreatesAnEntityTableUsingTheLimitedAppAccount() {
    assertThat(entities.getProperties().get("hibernate.hbm2ddl.auto")).isEqualTo("update");
    assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("users_app");
    assertThat(
            jdbc.queryForObject(
                "SELECT tableowner FROM pg_tables"
                    + " WHERE schemaname = 'users' AND tablename = 'p_dev_schema_probe'",
                String.class))
        .isEqualTo("users_app");
    jdbc.update("INSERT INTO users.p_dev_schema_probe (id, payload) VALUES (1, 'created')");
    assertThat(
            jdbc.queryForObject(
                "SELECT payload FROM users.p_dev_schema_probe WHERE id = 1", String.class))
        .isEqualTo("created");
    assertThat(
            jdbc.queryForObject(
                "SELECT has_schema_privilege(current_user, 'public', 'CREATE')", Boolean.class))
        .isFalse();
  }

  @TestConfiguration(proxyBeanMethods = false)
  @EntityScan(basePackageClasses = SchemaProbe.class)
  static class SchemaConfiguration {}

  @Entity
  @Table(name = "p_dev_schema_probe")
  static class SchemaProbe {
    @Id private Long id;
    private String payload;
  }
}
