package com.notfound.logistics.security;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.notfound.logistics.users.UsersApplication;
import com.notfound.logistics.users.application.service.UserService;
import com.notfound.logistics.users.domain.model.UserRoleEnum;
import com.notfound.logistics.users.infrastructure.security.ServiceAuthentication;
import com.notfound.logistics.users.presentation.dto.UserAuthorizationResponseDto;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
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

  private String bearer(String role) {
    Instant now = Instant.now();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer("logistics-users")
            .subject(USER_ID.toString())
            .issuedAt(now)
            .expiresAt(now.plusSeconds(300))
            .claim("username", "user01")
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
