package com.notfound.logistics.common.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.hibernate.annotations.SQLRestriction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
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

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = AuditEntityTest.TestApplication.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuditEntityTest {
  private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant UPDATED = Instant.parse("2026-01-01T01:00:00Z");
  private static final AtomicReference<String> ACTOR = new AtomicReference<>();
  private static final AtomicReference<Instant> NOW = new AtomicReference<>();

  @Container
  static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:18-alpine");

  @Autowired private FixtureRepository repository;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactionManager;

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
    registry.add("spring.datasource.username", DATABASE::getUsername);
    registry.add("spring.datasource.password", DATABASE::getPassword);
  }

  @BeforeEach
  void resetFixture() {
    jdbc.execute("TRUNCATE TABLE p_audit_fixture");
    ACTOR.set("creator");
    NOW.set(CREATED);
  }

  @Test
  void creationAndUpdateRecordTheActorWithoutRewritingTheCreator() {
    var entity = repository.saveAndFlush(new Fixture("before"));
    assertThat(entity.getCreatedAt()).isEqualTo(CREATED);
    assertThat(entity.getUpdatedAt()).isEqualTo(CREATED);
    assertThat(entity.getCreatedBy()).isEqualTo("creator");
    assertThat(entity.getUpdatedBy()).isEqualTo("creator");

    ACTOR.set("editor");
    NOW.set(UPDATED);
    entity.name = "after";
    repository.saveAndFlush(entity);
    var updated = repository.findById(entity.id).orElseThrow();
    assertThat(updated.getCreatedAt()).isEqualTo(CREATED);
    assertThat(updated.getCreatedBy()).isEqualTo("creator");
    assertThat(updated.getUpdatedAt()).isEqualTo(UPDATED);
    assertThat(updated.getUpdatedBy()).isEqualTo("editor");
  }

  @Test
  void softDeletionKeepsTheRowButHidesItFromIdListAndCountQueries() {
    var entity = repository.saveAndFlush(new Fixture("deleted"));
    ACTOR.set("deleter");
    NOW.set(UPDATED);
    entity.markDeleted("deleter", UPDATED);
    repository.saveAndFlush(entity);

    assertThat(repository.findById(entity.id)).isEmpty();
    assertThat(repository.findAll()).isEmpty();
    assertThat(repository.count()).isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM p_audit_fixture", Long.class)).isOne();
    assertThat(jdbc.queryForObject("SELECT deleted_by FROM p_audit_fixture", String.class))
        .isEqualTo("deleter");
    assertThat(
            jdbc.queryForObject("SELECT deleted_at FROM p_audit_fixture", java.sql.Timestamp.class)
                .toInstant())
        .isEqualTo(UPDATED);
  }

  @Test
  void failureRollsBackDeletionTogetherWithTheTransaction() {
    var entity = repository.saveAndFlush(new Fixture("active"));
    var transaction = new TransactionTemplate(transactionManager);
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    status -> {
                      var managed = repository.findById(entity.id).orElseThrow();
                      managed.markDeleted("deleter", UPDATED);
                      repository.flush();
                      throw new IllegalStateException("fixture failure");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("fixture failure");
    assertThat(repository.findById(entity.id).orElseThrow().getDeletedAt()).isNull();
    assertThat(repository.count()).isOne();
  }

  @Test
  void aMissingActorFailsInsteadOfInventingAnAuditor() {
    ACTOR.set(null);
    assertThatThrownBy(() -> repository.saveAndFlush(new Fixture("invalid")))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThat(repository.count()).isZero();
  }

  @Test
  void repeatedDeletionPreservesTheOriginalRecordAndRejectsInvalidActors() {
    var entity = new Fixture("example");
    entity.markDeleted("first", CREATED);
    entity.markDeleted("second", UPDATED);
    assertThat(entity.getDeletedAt()).isEqualTo(CREATED);
    assertThat(entity.getDeletedBy()).isEqualTo("first");
    assertThatIllegalArgumentException().isThrownBy(() -> entity.markDeleted(" ", UPDATED));
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @EntityScan(basePackageClasses = Fixture.class)
  @EnableJpaRepositories(
      basePackageClasses = FixtureRepository.class,
      considerNestedRepositories = true)
  @EnableJpaAuditing(auditorAwareRef = "auditor", dateTimeProviderRef = "auditClock")
  static class TestApplication {
    @Bean
    AuditorAware<String> auditor() {
      return () -> Optional.ofNullable(ACTOR.get());
    }

    @Bean
    DateTimeProvider auditClock() {
      return () -> Optional.of(NOW.get());
    }
  }

  interface FixtureRepository extends JpaRepository<Fixture, UUID> {}

  @Entity
  @Table(name = "p_audit_fixture")
  @SQLRestriction("deleted_at IS NULL")
  static class Fixture extends AuditEntity {
    @Id private UUID id = UUID.randomUUID();
    private String name;

    protected Fixture() {}

    Fixture(String name) {
      this.name = name;
    }
  }
}
