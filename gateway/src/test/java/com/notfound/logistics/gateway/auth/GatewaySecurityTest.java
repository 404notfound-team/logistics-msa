package com.notfound.logistics.gateway.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.web.reactive.function.server.RequestPredicates.path;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import com.notfound.logistics.gateway.auth.UsersAuthorizationClient.CurrentUser;
import com.notfound.logistics.gateway.auth.UsersAuthorizationClient.UsersUnavailableException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

@ActiveProfiles("test")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "JWT_SECRET=" + GatewaySecurityTest.SECRET,
      "GATEWAY_SERVICE_TOKEN=test-only-gateway-service-token",
      "eureka.client.enabled=false",
      "management.tracing.enabled=false"
    })
@Import(GatewaySecurityTest.DownstreamRoutes.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GatewaySecurityTest {
  static final String SECRET = "test-only-signing-key-for-gateway-module-tests-32bytes";
  private static final String OTHER_SECRET = "another-signing-key-that-gateway-must-reject-32b";
  private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final String USERNAME = "user01";

  @MockitoBean private UsersAuthorizationClient usersClient;
  @LocalServerPort private int port;
  private WebTestClient client;

  @BeforeEach
  void setUp() {
    client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
  }

  @Test
  void rejectsMissingToken() {
    expectError(request(HttpMethod.GET, "/api/orders", null), HttpStatus.UNAUTHORIZED);
    verify(usersClient, never()).fetch(any());
  }

  @Test
  void rejectsInvalidTokensWithoutConsultingUsers() {
    Instant now = Instant.now();
    for (String token :
        new String[] {
          token(OTHER_SECRET, USERNAME, USER_ID.toString(), "USER", now),
          token(SECRET, USERNAME, USER_ID.toString(), "USER", now.minusSeconds(3600)),
          token(SECRET, null, USER_ID.toString(), "USER", now),
          token(SECRET, USERNAME, null, "USER", now),
          token(SECRET, USERNAME, "not-a-uuid", "USER", now),
          token(SECRET, USERNAME, USER_ID.toString(), "ADMIN", now),
          token(SECRET, USERNAME, USER_ID.toString(), "USER", now, null),
          "not-a-jwt"
        }) {
      expectError(request(HttpMethod.GET, "/api/orders", token), HttpStatus.UNAUTHORIZED);
    }
    verify(usersClient, never()).fetch(any());
  }

  @Test
  void rejectsTokenThatNoLongerMatchesCurrentUser() {
    for (CurrentUser current :
        new CurrentUser[] {
          new CurrentUser(USER_ID, "other01", true, "USER", false, 0L),
          new CurrentUser(USER_ID, null, false, null, false, 0L),
          new CurrentUser(USER_ID, USERNAME, true, "MASTER", false, 0L),
          new CurrentUser(USER_ID, USERNAME, true, "USER", true, 0L),
          // 로그아웃으로 토큰 버전이 오른 사용자
          new CurrentUser(USER_ID, USERNAME, true, "USER", false, 1L)
        }) {
      given(usersClient.fetch(USER_ID)).willReturn(Mono.just(current));
      expectError(request(HttpMethod.GET, "/api/orders", token("USER")), HttpStatus.UNAUTHORIZED);
    }
  }

  @Test
  void reportsServiceUnavailableWhenUsersCannotBeConsulted() {
    given(usersClient.fetch(USER_ID))
        .willReturn(Mono.error(new UsersUnavailableException(new RuntimeException())));

    expectError(
        request(HttpMethod.GET, "/api/orders", token("USER")), HttpStatus.SERVICE_UNAVAILABLE);
  }

  @Test
  void forwardsOriginalAuthorizationWhenCurrentUserMatches() {
    currentUserIs("USER");
    String token = token("USER");

    request(HttpMethod.GET, "/api/orders", token)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.authorization")
        .isEqualTo("Bearer " + token);
  }

  @Test
  void forbidsRegularUserOnMasterOnlyEndpoints() {
    currentUserIs("USER");
    String token = token("USER");
    String other = "/api/users/" + UUID.randomUUID();

    for (var forbidden :
        new Object[][] {
          {HttpMethod.GET, "/api/users"},
          {HttpMethod.POST, "/api/users"},
          {HttpMethod.GET, other},
          {HttpMethod.PATCH, other},
          {HttpMethod.DELETE, other},
          {HttpMethod.POST, "/api/stocks"},
          {HttpMethod.DELETE, "/api/orders/" + UUID.randomUUID()}
        }) {
      expectError(
          request((HttpMethod) forbidden[0], (String) forbidden[1], token), HttpStatus.FORBIDDEN);
    }
  }

  @Test
  void letsRegularUserReadAndWithdrawThemselves() {
    currentUserIs("USER");

    request(HttpMethod.GET, "/api/users/me", token("USER")).exchange().expectStatus().isOk();
    request(HttpMethod.POST, "/api/users/signout", token("USER")).exchange().expectStatus().isOk();
    request(HttpMethod.DELETE, "/api/users/me", token("USER")).exchange().expectStatus().isOk();
  }

  @Test
  void letsMasterUseMasterOnlyEndpoints() {
    currentUserIs("MASTER");

    request(HttpMethod.GET, "/api/users", token("MASTER")).exchange().expectStatus().isOk();
    request(HttpMethod.DELETE, "/api/users/" + UUID.randomUUID(), token("MASTER"))
        .exchange()
        .expectStatus()
        .isOk();
  }

  @Test
  void passesPublicEndpointsWithoutConsultingUsersEvenWithStaleToken() {
    String expired =
        token(SECRET, USERNAME, USER_ID.toString(), "USER", Instant.now().minusSeconds(3600));

    request(HttpMethod.POST, "/api/users/signin", null).exchange().expectStatus().isOk();
    request(HttpMethod.POST, "/api/users/signup", expired).exchange().expectStatus().isOk();
    request(HttpMethod.POST, "/api/users/refresh", expired).exchange().expectStatus().isOk();
    request(HttpMethod.GET, "/api/users/signin", null).exchange().expectStatus().isOk();
    verify(usersClient, never()).fetch(any());
  }

  private void currentUserIs(String role) {
    given(usersClient.fetch(USER_ID))
        .willReturn(Mono.just(new CurrentUser(USER_ID, USERNAME, true, role, false, 0L)));
  }

  private WebTestClient.RequestHeadersSpec<?> request(HttpMethod method, String uri, String token) {
    return client
        .method(method)
        .uri(uri)
        .headers(
            headers -> {
              if (token != null) {
                headers.setBearerAuth(token);
              }
            });
  }

  private void expectError(WebTestClient.RequestHeadersSpec<?> request, HttpStatus status) {
    request
        .exchange()
        .expectStatus()
        .isEqualTo(status)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo(status.name())
        .jsonPath("$.authorization")
        .doesNotExist();
  }

  private String token(String role) {
    return token(SECRET, USERNAME, USER_ID.toString(), role, Instant.now());
  }

  private String token(
      String secret, String username, String userId, String role, Instant issuedAt) {
    return token(secret, username, userId, role, issuedAt, 0L);
  }

  private String token(
      String secret,
      String username,
      String userId,
      String role,
      Instant issuedAt,
      Long tokenVersion) {
    var key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    var encoder = new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(key));
    var claims =
        JwtClaimsSet.builder()
            .issuer("logistics-users")
            .issuedAt(issuedAt)
            .expiresAt(issuedAt.plusSeconds(900))
            .claim("role", role)
            .claim("is_delivery_staff", false);
    if (username != null) {
      claims.subject(username);
    }
    if (userId != null) {
      claims.claim("user_id", userId);
    }
    if (tokenVersion != null) {
      claims.claim("token_version", tokenVersion);
    }
    var header = JwsHeader.with(MacAlgorithm.HS256).build();
    return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
  }

  /** 하위 서비스 대신 받은 Authorization 헤더를 그대로 돌려줍니다. 라우터 함수가 Gateway 라우트보다 먼저 매칭됩니다. */
  @TestConfiguration(proxyBeanMethods = false)
  static class DownstreamRoutes {
    @Bean
    RouterFunction<ServerResponse> downstreamEchoRoutes() {
      return RouterFunctions.route(
          path("/api/**"),
          request ->
              ServerResponse.ok()
                  .bodyValue(
                      Map.of(
                          "authorization",
                          String.valueOf(
                              request.headers().firstHeader(HttpHeaders.AUTHORIZATION)))));
    }
  }
}
