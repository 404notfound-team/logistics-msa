package com.notfound.logistics.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.notfound.logistics.common.api.PageResponse;
import com.notfound.logistics.users.UsersApplication;
import com.notfound.logistics.users.application.service.UserService;
import com.notfound.logistics.users.domain.model.UserRoleEnum;
import com.notfound.logistics.users.infrastructure.security.ServiceAuthentication;
import com.notfound.logistics.users.presentation.dto.UserAuthorizationResponseDto;
import com.notfound.logistics.users.presentation.dto.UserCreateRequestDto;
import com.notfound.logistics.users.presentation.dto.UserResponseDto;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@ActiveProfiles("local")
@SpringBootTest(
    classes = UsersApplication.class,
    properties = {
      "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
      "spring.data.jpa.repositories.enabled=false",
      "users.audit.enabled=false",
      "users.jwt.secret=test-only-signing-key-for-users-module-tests-32bytes",
      "USERS_SERVICE_TOKEN=test-only-users-service-token",
      "GATEWAY_SERVICE_TOKEN=test-only-gateway-service-token",
      "users.internal-auth.clients.orders.token=test-only-orders-service-token",
      "users.internal-auth.clients.orders.allowed[0]=GET /internal/test/actor",
      "eureka.client.enabled=false",
      "management.tracing.enabled=false"
    })
@AutoConfigureMockMvc
@Import(UserSecurityTest.ProbeController.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UserSecurityTest {
  private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final String AUTHORIZATION_PATH = "/internal/users/" + USER_ID + "/authorization";

  @MockitoBean private UserService userService;

  @Autowired private MockMvc mvc;
  @Autowired private JwtEncoder jwtEncoder;

  @Test
  void internalApiRejectsMissingServiceToken() throws Exception {
    mvc.perform(get(AUTHORIZATION_PATH))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
  }

  @Test
  void internalApiRejectsUnknownServiceToken() throws Exception {
    mvc.perform(get(AUTHORIZATION_PATH).header("X-Service-Token", "forged"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void internalApiDoesNotAcceptUserJwt() throws Exception {
    mvc.perform(get(AUTHORIZATION_PATH).header(HttpHeaders.AUTHORIZATION, bearer("MASTER")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void internalApiForbidsServiceOutsideItsAllowedApis() throws Exception {
    mvc.perform(get(AUTHORIZATION_PATH).header("X-Service-Token", "test-only-orders-service-token"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    mvc.perform(
            get("/internal/test/actor")
                .header("X-Service-Token", "test-only-gateway-service-token"))
        .andExpect(status().isForbidden());
  }

  @Test
  void gatewayCanReadCurrentUserAuthorization() throws Exception {
    given(userService.getAuthorization(USER_ID))
        .willReturn(
            new UserAuthorizationResponseDto(USER_ID, "user01", true, UserRoleEnum.USER, false));

    mvc.perform(
            get(AUTHORIZATION_PATH).header("X-Service-Token", "test-only-gateway-service-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.username").value("user01"))
        .andExpect(jsonPath("$.data.active").value(true));
  }

  @Test
  void internalApiExposesCallingServiceAndOptionalActor() throws Exception {
    mvc.perform(
            get("/internal/test/actor")
                .header("X-Service-Token", "test-only-orders-service-token")
                .header("X-Actor-Username", "master01"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.service").value("orders"))
        .andExpect(jsonPath("$.actor").value("master01"));
    mvc.perform(
            get("/internal/test/actor").header("X-Service-Token", "test-only-orders-service-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.actor").value(""));
  }

  @Test
  void infraPingStaysOpen() throws Exception {
    mvc.perform(get("/internal/infra/ping")).andExpect(status().isOk());
  }

  @Test
  void externalApiUsesUsernameClaimAsPrincipalAndRoleAsAuthority() throws Exception {
    mvc.perform(
            get("/api/users/test/principal").header(HttpHeaders.AUTHORIZATION, bearer("MASTER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("user01"))
        .andExpect(jsonPath("$.authorities").value("ROLE_MASTER"));
  }

  @Test
  void regularUserCannotListOrReadOtherUsers() throws Exception {
    mvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION, bearer("USER")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    mvc.perform(
            get("/api/users/" + UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, bearer("USER")))
        .andExpect(status().isForbidden());
  }

  @Test
  void regularUserCannotManageUsers() throws Exception {
    mvc.perform(
            post("/api/users")
                .header(HttpHeaders.AUTHORIZATION, bearer("USER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            delete("/api/users/" + UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, bearer("USER")))
        .andExpect(status().isForbidden());
  }

  @Test
  void userReadsThemselvesByJwtUserId() throws Exception {
    given(userService.getUser(USER_ID)).willReturn(userResponse());

    mvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer("USER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.username").value("user01"))
        .andExpect(jsonPath("$.data.hub_id").exists())
        .andExpect(jsonPath("$.data.password_hash").doesNotExist());
  }

  @Test
  void userWithdrawsWithJwtUserIdAndSubject() throws Exception {
    mvc.perform(delete("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer("USER")))
        .andExpect(status().isNoContent());

    verify(userService).withdraw(USER_ID, "user01");
  }

  @Test
  void masterCreatesUserWithLocation() throws Exception {
    given(userService.createUser(any(UserCreateRequestDto.class))).willReturn(userResponse());

    mvc.perform(
            post("/api/users")
                .header(HttpHeaders.AUTHORIZATION, bearer("MASTER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"username":"user02","name":"사용자","password":"Passw0rd!","role":"USER",
                    "slack_id":"slack","hub_id":"%s"}
                    """
                        .formatted(UUID.randomUUID())))
        .andExpect(status().isCreated())
        .andExpect(header().string(HttpHeaders.LOCATION, "/api/users/" + USER_ID));
  }

  @Test
  void masterCreateRejectsBothAffiliations() throws Exception {
    mvc.perform(
            post("/api/users")
                .header(HttpHeaders.AUTHORIZATION, bearer("MASTER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"username":"user02","name":"사용자","password":"Passw0rd!","role":"USER",
                    "slack_id":"slack","hub_id":"%s","company_id":"%s"}
                    """
                        .formatted(UUID.randomUUID(), UUID.randomUUID())))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field_errors[0].field").value("$"));
  }

  @Test
  void masterListsWithDefaultSortAndPkTieBreaker() throws Exception {
    given(userService.getUsers(any(), any())).willReturn(new PageResponse<>(List.of(), 0, 20, 0));

    mvc.perform(
            get("/api/users")
                .param("role", "USER")
                .header(HttpHeaders.AUTHORIZATION, bearer("MASTER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total_elements").value(0));

    ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
    verify(userService).getUsers(any(), pageable.capture());
    assertThat(pageable.getValue().getSort())
        .isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")));
  }

  @Test
  void masterListRejectsUnknownSortAndOversizedPage() throws Exception {
    mvc.perform(
            get("/api/users")
                .param("sort", "password_hash,asc")
                .header(HttpHeaders.AUTHORIZATION, bearer("MASTER")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_SORT"));
    mvc.perform(
            get("/api/users")
                .param("size", "101")
                .header(HttpHeaders.AUTHORIZATION, bearer("MASTER")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void masterDeletesUserAsJwtSubject() throws Exception {
    UUID target = UUID.randomUUID();

    mvc.perform(delete("/api/users/" + target).header(HttpHeaders.AUTHORIZATION, bearer("MASTER")))
        .andExpect(status().isNoContent());

    verify(userService).deleteUser(eq(target), eq("user01"));
  }

  private UserResponseDto userResponse() {
    return new UserResponseDto(
        USER_ID,
        "user01",
        "사용자",
        UserRoleEnum.USER,
        "slack",
        null,
        UUID.randomUUID(),
        false,
        Instant.now(),
        Instant.now());
  }

  private String bearer(String role) {
    Instant now = Instant.now();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer("logistics-users")
            .subject("user01")
            .issuedAt(now)
            .expiresAt(now.plusSeconds(300))
            .claim("user_id", USER_ID.toString())
            .claim("role", role)
            .claim("is_delivery_staff", false)
            .build();
    String token =
        jwtEncoder
            .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
            .getTokenValue();
    return "Bearer " + token;
  }

  @RestController
  static class ProbeController {
    @GetMapping("/internal/test/actor")
    Map<String, String> actor(Authentication authentication) {
      var service = (ServiceAuthentication) authentication;
      String actor = service.getActorUsername();
      return Map.of("service", service.getServiceName(), "actor", actor == null ? "" : actor);
    }

    @GetMapping("/api/users/test/principal")
    Map<String, String> principal(Authentication authentication) {
      return Map.of(
          "name",
          authentication.getName(),
          "authorities",
          authentication.getAuthorities().stream()
              .map(GrantedAuthority::getAuthority)
              .collect(Collectors.joining(",")));
    }
  }
}
