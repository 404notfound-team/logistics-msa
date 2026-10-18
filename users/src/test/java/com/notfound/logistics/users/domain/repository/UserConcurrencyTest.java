package com.notfound.logistics.users.domain.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.notfound.logistics.users.UsersApplication;
import com.notfound.logistics.users.domain.model.User;
import com.notfound.logistics.users.domain.model.UserRoleEnum;
import com.notfound.logistics.users.infrastructure.audit.SignupAuditScope;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

/**
 * 같은 사용자를 두 요청이 겹쳐 변경하는 순서를 트랜잭션으로 직접 재현합니다. 먼저 조회한 요청(stale)이 저장하기 전에 다른 요청(winner)이 끝까지 커밋되면,
 * stale 요청은 실패하고 winner의 변경이 그대로 남아야 합니다.
 */
@ActiveProfiles("local")
@SpringBootTest(
    classes = UsersApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "eureka.client.enabled=false",
      "management.tracing.enabled=false",
      "springdoc.api-docs.enabled=false",
      "users.jwt.secret=test-only-signing-key-for-users-module-tests-32bytes",
      "USERS_SERVICE_TOKEN=test-only-users-service-token",
      "GATEWAY_SERVICE_TOKEN=test-only-gateway-service-token"
    })
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UserConcurrencyTest {
  private static final Instant WITHDRAWN_AT = Instant.parse("2026-10-18T01:00:00Z");
  private static final Instant DELETED_AT = Instant.parse("2026-10-18T02:00:00Z");

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

  @Autowired private UserRepository userRepository;
  @Autowired private TransactionTemplate transaction;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void cleanUp() {
    SecurityContextHolder.clearContext();
    jdbc.update("DELETE FROM p_users");
  }

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void adminUpdateDoesNotReviveUserWithdrawnMeanwhile() {
    UUID id = saveUser("user01");

    assertThatThrownBy(
            () ->
                runStaleAfterWinner(
                    id,
                    "master01",
                    user -> rename(user, "관리자수정"),
                    "user01",
                    user -> user.markDeleted("user01", WITHDRAWN_AT)))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);

    assertThat(userRepository.findById(id)).isEmpty();
    Map<String, Object> row = row(id);
    assertThat(((Timestamp) row.get("deleted_at")).toInstant()).isEqualTo(WITHDRAWN_AT);
    assertThat(row.get("deleted_by")).isEqualTo("user01");
    assertThat(row.get("name")).isEqualTo("사용자");
  }

  @Test
  void adminDeleteDoesNotOverwriteWithdrawalCommittedMeanwhile() {
    UUID id = saveUser("user01");

    assertThatThrownBy(
            () ->
                runStaleAfterWinner(
                    id,
                    "master01",
                    user -> user.markDeleted("master01", DELETED_AT),
                    "user01",
                    user -> user.markDeleted("user01", WITHDRAWN_AT)))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);

    Map<String, Object> row = row(id);
    assertThat(((Timestamp) row.get("deleted_at")).toInstant()).isEqualTo(WITHDRAWN_AT);
    assertThat(row.get("deleted_by")).isEqualTo("user01");
  }

  @Test
  void withdrawalLosingToConcurrentUpdateSucceedsOnRetry() {
    UUID id = saveUser("user01");

    assertThatThrownBy(
            () ->
                runStaleAfterWinner(
                    id,
                    "user01",
                    user -> user.markDeleted("user01", WITHDRAWN_AT),
                    "master01",
                    user -> rename(user, "관리자수정")))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    assertThat(userRepository.findById(id)).get().extracting(User::getName).isEqualTo("관리자수정");

    authenticateAs("user01");
    transaction.executeWithoutResult(
        status -> userRepository.findById(id).orElseThrow().markDeleted("user01", WITHDRAWN_AT));

    assertThat(userRepository.findById(id)).isEmpty();
    assertThat(row(id).get("deleted_by")).isEqualTo("user01");
    assertThat(row(id).get("name")).isEqualTo("관리자수정");
  }

  /**
   * stale 요청이 사용자를 조회해 둔 사이 winner 요청이 별도 트랜잭션으로 조회·변경·커밋을 마치고, 그 뒤 stale 요청이 조회해 둔 엔티티를 변경해 커밋합니다.
   */
  private void runStaleAfterWinner(
      UUID id,
      String staleActor,
      Consumer<User> staleChange,
      String winnerActor,
      Consumer<User> winnerChange) {
    TransactionTemplate separateTransaction = new TransactionTemplate(transactionManager);
    separateTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

    authenticateAs(staleActor);
    transaction.executeWithoutResult(
        status -> {
          User stale = userRepository.findById(id).orElseThrow();

          authenticateAs(winnerActor);
          separateTransaction.executeWithoutResult(
              winnerStatus -> winnerChange.accept(userRepository.findById(id).orElseThrow()));

          authenticateAs(staleActor);
          staleChange.accept(stale);
        });
  }

  private UUID saveUser(String username) {
    User user =
        User.create(
            username, "사용자", "hash", "slack", UserRoleEnum.USER, null, UUID.randomUUID(), false);
    return SignupAuditScope.run(() -> userRepository.save(user)).getId();
  }

  private void rename(User user, String name) {
    user.update(
        name,
        user.getSlackId(),
        user.getPasswordHash(),
        user.getRole(),
        user.isDeliveryStaff(),
        user.getCompanyId(),
        user.getHubId());
  }

  private Map<String, Object> row(UUID id) {
    return jdbc.queryForMap("SELECT name, deleted_at, deleted_by FROM p_users WHERE id = ?", id);
  }

  private void authenticateAs(String username) {
    Instant now = Instant.now();
    Jwt jwt =
        Jwt.withTokenValue("token")
            .header("alg", "HS256")
            .subject(username)
            .issuedAt(now)
            .expiresAt(now.plusSeconds(60))
            .build();
    SecurityContextHolder.getContext()
        .setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
  }
}
