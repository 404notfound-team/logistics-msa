package com.notfound.logistics.orders.infrastructure.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.notfound.logistics.common.audit.AuditEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.properties.hibernate.default_schema=public"
    })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = OrdersAuditingIntegrationTest.TestApplication.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrdersAuditingIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> DATABASE =
      new PostgreSQLContainer<>("postgres:18-alpine")
          .withLabel("com.notfound.logistics.task", "orders-27");

  @Autowired private FixtureRepository repository;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private OrdersAuditorAware auditor;

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
    registry.add("spring.datasource.username", DATABASE::getUsername);
    registry.add("spring.datasource.password", DATABASE::getPassword);
  }

  @BeforeEach
  void reset() {
    SecurityContextHolder.clearContext();
    jdbc.execute("TRUNCATE TABLE p_orders_audit_fixture");
  }

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void createAndUpdateUseVerifiedUsernamesAndPreserveTheCreator() {
    authenticate("creator");
    var saved = repository.saveAndFlush(new Fixture("before"));
    var persisted = repository.findById(saved.id).orElseThrow();
    assertThat(saved.getCreatedBy()).isEqualTo("creator");
    assertThat(saved.getUpdatedBy()).isEqualTo("creator");
    assertThat(saved.getCreatedAt()).isNotNull();
    assertThat(saved.getUpdatedAt()).isEqualTo(saved.getCreatedAt());

    authenticate("editor");
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              var managed = repository.findById(saved.id).orElseThrow();
              managed.name = "after";
              repository.flush();
            });
    var updated = repository.findById(saved.id).orElseThrow();
    assertThat(updated.getCreatedBy()).isEqualTo("creator");
    assertThat(updated.getCreatedAt()).isEqualTo(persisted.getCreatedAt());
    assertThat(updated.getUpdatedBy()).isEqualTo("editor");
    assertThat(updated.getUpdatedAt()).isAfterOrEqualTo(persisted.getUpdatedAt());
  }

  @Test
  void missingUnauthenticatedAnonymousAndInvalidUsernamesCannotCreateRows() {
    assertRejectedActor(null);
    assertRejectedActor(
        UsernamePasswordAuthenticationToken.unauthenticated("unverified", "ignored"));
    assertRejectedActor(
        new AnonymousAuthenticationToken(
            "fixture", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
    assertRejectedActor(UsernamePasswordAuthenticationToken.authenticated(" ", null, List.of()));
    assertRejectedActor(
        UsernamePasswordAuthenticationToken.authenticated("x".repeat(101), null, List.of()));
  }

  @Test
  void missingActorRollsBackAnUpdateInsteadOfPreservingAStaleEditor() {
    authenticate("creator");
    var saved = repository.saveAndFlush(new Fixture("before"));
    var persisted = repository.findById(saved.id).orElseThrow();
    SecurityContextHolder.clearContext();
    assertThatThrownBy(
            () ->
                new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                        status -> {
                          var managed = repository.findById(saved.id).orElseThrow();
                          managed.name = "after";
                          repository.flush();
                        }))
        .hasRootCauseInstanceOf(IllegalStateException.class);
    var retained = repository.findById(saved.id).orElseThrow();
    assertThat(retained.name).isEqualTo("before");
    assertThat(retained.getUpdatedBy()).isEqualTo("creator");
    assertThat(retained.getUpdatedAt()).isEqualTo(persisted.getUpdatedAt());
  }

  private void assertRejectedActor(Authentication authentication) {
    SecurityContextHolder.getContext().setAuthentication(authentication);
    assertThatThrownBy(() -> repository.saveAndFlush(new Fixture("invalid")))
        .hasRootCauseInstanceOf(IllegalStateException.class);
    assertThat(repository.count()).isZero();
  }

  @Test
  void recoveryUsesTheStoredUsernameAndRestoresThePreviousContextAfterFailure() {
    var previous = SecurityContextHolder.getContext();
    previous.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(
            "admin", null, List.of(new SimpleGrantedAuthority("ROLE_MASTER"))));
    assertThatThrownBy(
            () ->
                auditor.runAsPersistedUser(
                    "original-user",
                    () -> {
                      assertThat(
                              SecurityContextHolder.getContext()
                                  .getAuthentication()
                                  .getAuthorities())
                          .isEmpty();
                      var saved = repository.saveAndFlush(new Fixture("recovered"));
                      assertThat(saved.getCreatedBy()).isEqualTo("original-user");
                      throw new IllegalStateException("fixture failure");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("fixture failure");
    assertThat(SecurityContextHolder.getContext()).isSameAs(previous);
    assertThat(auditor.getCurrentAuditor()).contains("admin");
    assertThat(previous.getAuthentication().getAuthorities())
        .extracting(GrantedAuthority::getAuthority)
        .containsExactly("ROLE_MASTER");
  }

  private void authenticate(String username) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(username, null, List.of()));
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @EntityScan(basePackageClasses = Fixture.class)
  @EnableJpaRepositories(
      basePackageClasses = FixtureRepository.class,
      considerNestedRepositories = true)
  @Import({JpaAuditingConfig.class, OrdersAuditorAware.class})
  static class TestApplication {}

  interface FixtureRepository extends JpaRepository<Fixture, UUID> {}

  @Entity
  @Table(name = "p_orders_audit_fixture")
  static class Fixture extends AuditEntity {
    @Id private UUID id = UUID.randomUUID();
    private String name;

    protected Fixture() {}

    Fixture(String name) {
      this.name = name;
    }
  }
}
