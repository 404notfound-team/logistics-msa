package com.notfound.logistics.users.domain.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.BDDMockito.given;

import com.notfound.logistics.common.api.ApiResponse;
import com.notfound.logistics.users.UsersApplication;
import com.notfound.logistics.users.application.dto.UpdateUserCommand;
import com.notfound.logistics.users.application.service.UserService;
import com.notfound.logistics.users.domain.model.User;
import com.notfound.logistics.users.domain.model.UserRoleEnum;
import com.notfound.logistics.users.infrastructure.audit.SignupAuditScope;
import com.notfound.logistics.users.infrastructure.client.HubLookupClient;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

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
class UserPersistenceTest {
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
  @Autowired private JdbcTemplate jdbc;
  @Autowired private UserService userService;
  @MockitoBean private HubLookupClient hubLookupClient;

  @BeforeEach
  void cleanUp() {
    SecurityContextHolder.clearContext();
    // 논리 삭제된 행도 username을 점유하므로 테스트 사이에는 테이블을 비웁니다.
    jdbc.update("DELETE FROM p_users");
  }

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void signupIsAuditedAsSystemActorAndLaterChangesAsAuthenticatedUser() {
    User saved = SignupAuditScope.run(() -> userRepository.save(user("user01")));
    assertThat(saved.getCreatedBy()).isEqualTo("signup-service");

    authenticateAs("master01");
    transaction.executeWithoutResult(
        status -> {
          User user = userRepository.findById(saved.getId()).orElseThrow();
          user.update("바뀐이름", "slack", "hash", UserRoleEnum.USER, true, null, user.getHubId());
        });

    User updated = userRepository.findById(saved.getId()).orElseThrow();
    assertThat(updated.getCreatedBy()).isEqualTo("signup-service");
    assertThat(updated.getUpdatedBy()).isEqualTo("master01");
  }

  @Test
  void aPageBeyondJpaOffsetRangeReturnsEmptyContentWithTheFilteredCount() {
    SignupAuditScope.run(() -> userRepository.save(user("user01")));
    SignupAuditScope.run(() -> userRepository.save(user("user02")));
    var condition = new UserSearchCondition("user01", null, null, null, null);
    var result = userService.getUsers(condition, PageRequest.of(Integer.MAX_VALUE, 20));

    assertThat(result.getContent()).isEmpty();
    assertThat(result.getNumber()).isEqualTo(Integer.MAX_VALUE);
    assertThat(result.getSize()).isEqualTo(20);
    assertThat(result.getTotalElements()).isEqualTo(1);
    assertThat(result.getTotalPages()).isEqualTo(1);
  }

  @Test
  void notificationIdentitiesAreLimitedDeduplicatedAndExcludeDeletedUsers() {
    User buyer = SignupAuditScope.run(() -> userRepository.save(user("buyer01")));
    User driver = SignupAuditScope.run(() -> userRepository.save(user("driver01")));
    SignupAuditScope.run(() -> userRepository.save(user("other01")));
    assertThat(userService.getIdentities("buyer01", driver.getId()))
        .extracting(identity -> identity.username())
        .containsExactly("buyer01", "driver01");
    assertThat(userService.getIdentities("buyer01", buyer.getId())).hasSize(1);
    authenticateAs("master01");
    transaction.executeWithoutResult(
        status ->
            userRepository
                .findById(driver.getId())
                .orElseThrow()
                .markDeleted("master01", Instant.now()));
    assertThat(userService.getIdentities("buyer01", driver.getId()))
        .extracting(identity -> identity.username())
        .containsExactly("buyer01");
    assertThat(userService.getIdentities("missing", driver.getId())).isEmpty();
  }

  @Test
  void writeWithoutActorFails() {
    assertThatThrownBy(() -> userRepository.save(user("user01")))
        .hasRootCauseInstanceOf(IllegalStateException.class);
  }

  @Test
  void deletedUserIsHiddenButItsUsernameStaysTaken() {
    User saved = SignupAuditScope.run(() -> userRepository.save(user("user01")));

    authenticateAs("user01");
    transaction.executeWithoutResult(
        status ->
            userRepository
                .findById(saved.getId())
                .orElseThrow()
                .markDeleted("user01", Instant.now()));

    assertThat(userRepository.findById(saved.getId())).isEmpty();
    assertThat(userRepository.findByUsername("user01")).isEmpty();
    assertThat(userRepository.existsByUsernameIncludingDeleted("user01")).isTrue();
    assertThat(userRepository.existsByUsernameIncludingDeleted("user99")).isFalse();
  }

  @Test
  void concurrentAccountUpdateDoesNotRestoreWithdrawnUser() throws Exception {
    User saved = SignupAuditScope.run(() -> userRepository.save(user("user01")));
    UUID nextHub = UUID.randomUUID();
    var lookupStarted = new CountDownLatch(1);
    var finishLookup = new CountDownLatch(1);
    given(hubLookupClient.findById(nextHub))
        .willAnswer(
            invocation -> {
              lookupStarted.countDown();
              assertThat(finishLookup.await(10, TimeUnit.SECONDS)).isTrue();
              return new ApiResponse<>(new HubLookupClient.AffiliationResponse(nextHub));
            });

    var workers = Executors.newFixedThreadPool(2);
    try {
      var update =
          workers.submit(
              () -> {
                authenticateAs("master01");
                try {
                  return userService.updateUser(
                      saved.getId(),
                      new UpdateUserCommand(null, null, null, null, null, null, nextHub));
                } finally {
                  SecurityContextHolder.clearContext();
                }
              });
      assertThat(lookupStarted.await(10, TimeUnit.SECONDS)).isTrue();
      var withdrawal =
          workers.submit(
              () -> {
                authenticateAs("user01");
                try {
                  userService.withdraw(saved.getId(), "user01");
                } finally {
                  SecurityContextHolder.clearContext();
                }
              });

      // 수정이 멈춘 동안 탈퇴가 커밋되거나, DB에서 수정의 행 잠금을 기다릴 때까지 확인합니다.
      await()
          .atMost(Duration.ofSeconds(10))
          .until(
              () ->
                  withdrawal.isDone()
                      || Boolean.TRUE.equals(
                          jdbc.queryForObject(
                              """
                              SELECT EXISTS (
                                SELECT 1 FROM pg_stat_activity
                                WHERE datname = current_database()
                                  AND wait_event_type = 'Lock'
                                  AND cardinality(pg_blocking_pids(pid)) > 0
                              )
                              """,
                              Boolean.class)));
      finishLookup.countDown();
      update.get(10, TimeUnit.SECONDS);
      withdrawal.get(10, TimeUnit.SECONDS);

      assertThat(userRepository.findById(saved.getId())).isEmpty();
      assertThat(userRepository.findByUsername("user01")).isEmpty();
      assertThat(userService.findActiveUser(saved.getId())).isEmpty();
    } finally {
      finishLookup.countDown();
      workers.shutdownNow();
      assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void searchMatchesConditionsAndExcludesDeletedUsers() {
    User kim = SignupAuditScope.run(() -> userRepository.save(user("kim01")));
    SignupAuditScope.run(() -> userRepository.save(user("lee01")));
    User deleted = SignupAuditScope.run(() -> userRepository.save(user("kim02")));
    authenticateAs("master01");
    transaction.executeWithoutResult(
        status ->
            userRepository
                .findById(deleted.getId())
                .orElseThrow()
                .markDeleted("master01", Instant.now()));

    var page =
        userRepository.findAll(
            UserSpecifications.matching(
                new UserSearchCondition("kim", null, UserRoleEnum.USER, null, null)),
            PageRequest.of(0, 20));

    assertThat(page.getContent()).extracting(User::getId).containsExactly(kim.getId());
    assertThat(page.getTotalElements()).isEqualTo(1);

    var wildcard =
        userRepository.findAll(
            UserSpecifications.matching(new UserSearchCondition("%", null, null, null, null)),
            PageRequest.of(0, 20));
    assertThat(wildcard.getContent()).isEmpty();
  }

  private User user(String username) {
    return User.create(
        username, "사용자", "hash", "slack", UserRoleEnum.USER, null, UUID.randomUUID(), false);
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
