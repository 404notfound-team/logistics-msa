package com.notfound.logistics.users.domain.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notfound.logistics.common.api.ApiResponse;
import com.notfound.logistics.users.UsersApplication;
import com.notfound.logistics.users.domain.model.User;
import com.notfound.logistics.users.domain.model.UserRoleEnum;
import com.notfound.logistics.users.infrastructure.audit.SignupAuditScope;
import com.notfound.logistics.users.infrastructure.client.HubLookupClient;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

@ActiveProfiles("local")
@SpringBootTest(
    classes = UsersApplication.class,
    properties = {
      "eureka.client.enabled=false",
      "management.tracing.enabled=false",
      "springdoc.api-docs.enabled=false",
      "users.jwt.secret=test-only-signing-key-for-users-module-tests-32bytes",
      "USERS_SERVICE_TOKEN=test-only-users-service-token",
      "GATEWAY_SERVICE_TOKEN=test-only-gateway-service-token"
    })
@AutoConfigureMockMvc
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UserPersistenceTest {
  private static final String PASSWORD = "Passw0rd!";

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
  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JwtDecoder jwtDecoder;
  @Autowired private PasswordEncoder passwordEncoder;
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

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"USER", "MASTER"})
  void publicSignupCannotGrantAdministrativePrivileges(String requestedRole) throws Exception {
    UUID hubId = UUID.randomUUID();
    given(hubLookupClient.findByName("서울"))
        .willReturn(new ApiResponse<>(List.of(new HubLookupClient.HubLookupResponse(hubId, "서울"))));
    var request =
        new HashMap<String, Object>(
            Map.of(
                "username", "user01",
                "name", "사용자",
                "password", PASSWORD,
                "slack_id", "slack",
                "hub_name", "서울",
                "is_delivery_staff", true));
    if (requestedRole != null) {
      request.put("role", requestedRole);
    }

    mvc.perform(
            post("/api/users/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isCreated());

    User saved = userRepository.findByUsername("user01").orElseThrow();
    assertThat(saved.getRole()).isEqualTo(UserRoleEnum.USER);
    assertThat(saved.isDeliveryStaff()).isFalse();
    assertThat(saved.getHubId()).isEqualTo(hubId);
    assertThat(saved.getCreatedBy()).isEqualTo("signup-service");

    String authorization = "Bearer " + signIn("user01");
    Jwt token = jwtDecoder.decode(authorization.substring(7));
    assertThat(token.getClaimAsString("role")).isEqualTo("USER");
    assertThat(token.getClaimAsBoolean("is_delivery_staff")).isFalse();
    mvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION, authorization))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/users")
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            patch("/api/users/" + saved.getId())
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"MASTER\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            delete("/api/users/" + saved.getId()).header(HttpHeaders.AUTHORIZATION, authorization))
        .andExpect(status().isForbidden());
  }

  @Test
  void authenticatedMasterCanStillCreateAnotherMaster() throws Exception {
    SignupAuditScope.run(
        () ->
            userRepository.save(
                User.create(
                    "master01",
                    "관리자",
                    passwordEncoder.encode(PASSWORD),
                    "slack",
                    UserRoleEnum.MASTER,
                    null,
                    null,
                    false)));
    String authorization = "Bearer " + signIn("master01");

    mvc.perform(
            post("/api/users")
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"username":"master02","name":"새관리자","password":"Passw0rd!",
                    "slack_id":"slack","role":"MASTER"}
                    """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.role").value("MASTER"));

    User created = userRepository.findByUsername("master02").orElseThrow();
    assertThat(created.getRole()).isEqualTo(UserRoleEnum.MASTER);
    assertThat(created.getCreatedBy()).isEqualTo("master01");
  }

  private String signIn(String username) throws Exception {
    var response =
        mvc.perform(
                post("/api/users/signin")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            Map.of("username", username, "password", PASSWORD))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    return objectMapper
        .readTree(response.getContentAsString())
        .path("data")
        .path("access_token")
        .asText();
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
