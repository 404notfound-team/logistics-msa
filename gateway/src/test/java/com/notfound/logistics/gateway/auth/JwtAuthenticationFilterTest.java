package com.notfound.logistics.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import java.net.ConnectException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

class JwtAuthenticationFilterTest {
  private static final String SECRET = "test-only-signing-key-for-gateway-module-tests-32bytes";
  private static final String OTHER_SECRET = "another-signing-key-that-gateway-must-reject-32b";
  private static final String SERVICE_TOKEN = "test-only-gateway-service-token";
  private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final String USERNAME = "user01";

  private final AtomicInteger usersCalls = new AtomicInteger();
  private final AtomicReference<ClientRequest> usersRequest = new AtomicReference<>();

  @Test
  void rejectsMissingToken() {
    var result = run(MockServerHttpRequest.get("/api/orders"), usersResponds(user(true, "USER")));

    assertThat(result.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(usersCalls).hasValue(0);
  }

  @Test
  void rejectsTokenSignedWithAnotherKey() {
    var result =
        run(
            authorized("/api/orders", token(OTHER_SECRET, USERNAME, "USER", Instant.now())),
            usersResponds(user(true, "USER")));

    assertThat(result.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(usersCalls).hasValue(0);
  }

  @Test
  void rejectsExpiredToken() {
    var result =
        run(
            authorized(
                "/api/orders", token(SECRET, USERNAME, "USER", Instant.now().minusSeconds(3600))),
            usersResponds(user(true, "USER")));

    assertThat(result.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void rejectsTokenWithoutSubject() {
    var result =
        run(
            authorized("/api/orders", token(SECRET, null, "USER", Instant.now())),
            usersResponds(user(true, "USER")));

    assertThat(result.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(usersCalls).hasValue(0);
  }

  @Test
  void rejectsTokenWithoutUserId() {
    var result =
        run(
            authorized("/api/orders", token(SECRET, USERNAME, null, "USER", Instant.now())),
            usersResponds(user(true, "USER")));

    assertThat(result.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(usersCalls).hasValue(0);
  }

  @Test
  void rejectsTokenWhoseUserIdIsNotUuid() {
    var result =
        run(
            authorized("/api/orders", token(SECRET, USERNAME, "not-a-uuid", "USER", Instant.now())),
            usersResponds(user(true, "USER")));

    assertThat(result.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(usersCalls).hasValue(0);
  }

  @Test
  void rejectsTokenWhoseUsernameNoLongerMatches() {
    var result =
        run(
            authorized("/api/orders", token(SECRET, "other01", "USER", Instant.now())),
            usersResponds(user(true, "USER")));

    assertThat(result.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void rejectsWithdrawnUser() {
    var result =
        run(authorized("/api/orders", validToken("USER")), usersResponds(user(false, null)));

    assertThat(result.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void rejectsTokenWhoseRoleNoLongerMatches() {
    var result =
        run(authorized("/api/orders", validToken("MASTER")), usersResponds(user(true, "USER")));

    assertThat(result.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void rejectsTokenWhoseDeliveryStaffFlagNoLongerMatches() {
    var result =
        run(
            authorized("/api/orders", validToken("USER")),
            usersResponds(
                """
                {"data":{"user_id":"%s","username":"%s","active":true,"role":"USER",
                "is_delivery_staff":true}}
                """
                    .formatted(USER_ID, USERNAME)));

    assertThat(result.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void forbidsUserOnMasterOnlyEndpoint() {
    var request =
        MockServerHttpRequest.method(HttpMethod.POST, "/api/users")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken("USER"));

    var result = run(request, usersResponds(user(true, "USER")));

    assertThat(result.status()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void forbidsUserFromListingOrReadingOtherUsers() {
    var list = run(authorized("/api/users", validToken("USER")), usersResponds(user(true, "USER")));
    var detail =
        run(
            authorized("/api/users/" + UUID.randomUUID(), validToken("USER")),
            usersResponds(user(true, "USER")));

    assertThat(list.status()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(detail.status()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void letsUserReadAndWithdrawThemselves() {
    var read =
        run(authorized("/api/users/me", validToken("USER")), usersResponds(user(true, "USER")));
    var withdraw =
        run(
            MockServerHttpRequest.delete("/api/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken("USER")),
            usersResponds(user(true, "USER")));

    assertThat(read.forwarded()).isNotNull();
    assertThat(withdraw.forwarded()).isNotNull();
  }

  @Test
  void letsMasterListUsers() {
    var result =
        run(authorized("/api/users", validToken("MASTER")), usersResponds(user(true, "MASTER")));

    assertThat(result.forwarded()).isNotNull();
  }

  @Test
  void reportsServiceUnavailableWhenUsersFails() {
    var result =
        run(
            authorized("/api/orders", validToken("USER")),
            request -> {
              usersCalls.incrementAndGet();
              return Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR).build());
            });

    assertThat(result.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
  }

  @Test
  void reportsServiceUnavailableWhenUsersRejectsGatewayServiceToken() {
    var result =
        run(
            authorized("/api/orders", validToken("USER")),
            request -> Mono.just(ClientResponse.create(HttpStatus.UNAUTHORIZED).build()));

    assertThat(result.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
  }

  @Test
  void reportsServiceUnavailableWhenUsersIsUnreachable() {
    var result =
        run(
            authorized("/api/orders", validToken("USER")),
            request ->
                Mono.error(
                    new WebClientRequestException(
                        new ConnectException("refused"),
                        HttpMethod.GET,
                        URI.create("http://users"),
                        new HttpHeaders())));

    assertThat(result.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
  }

  @Test
  void forwardsOriginalAuthorizationWhenCurrentUserMatches() {
    String token = validToken("USER");

    var result = run(authorized("/api/orders", token), usersResponds(user(true, "USER")));

    assertThat(result.forwarded()).isNotNull();
    assertThat(result.forwarded().getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
        .isEqualTo("Bearer " + token);
  }

  @Test
  void consultsUsersWithServiceTokenInsteadOfUserJwt() {
    run(authorized("/api/orders", validToken("USER")), usersResponds(user(true, "USER")));

    HttpHeaders headers = usersRequest.get().headers();
    assertThat(usersRequest.get().url().getPath())
        .isEqualTo("/internal/users/" + USER_ID + "/authorization");
    assertThat(headers.getFirst("X-Service-Token")).isEqualTo(SERVICE_TOKEN);
    assertThat(headers.containsKey(HttpHeaders.AUTHORIZATION)).isFalse();
  }

  @Test
  void refusesToStartWithoutServiceToken() {
    assertThatThrownBy(
            () ->
                new JwtAuthenticationFilter(
                    new JwtConfiguration().jwtDecoder(SECRET),
                    new ObjectMapper(),
                    WebClient.builder(),
                    " "))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void passesPublicEndpointsWithoutConsultingUsers() {
    var result =
        run(MockServerHttpRequest.post("/api/users/signin"), usersResponds(user(true, "USER")));

    assertThat(result.forwarded()).isNotNull();
    assertThat(usersCalls).hasValue(0);
  }

  private Result run(MockServerHttpRequest.BaseBuilder<?> request, ExchangeFunction users) {
    var filter =
        new JwtAuthenticationFilter(
            new JwtConfiguration().jwtDecoder(SECRET),
            new ObjectMapper(),
            WebClient.builder().exchangeFunction(users),
            SERVICE_TOKEN);
    var exchange = MockServerWebExchange.from(request);
    AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

    filter
        .filter(
            exchange,
            next -> {
              forwarded.set(next);
              return Mono.empty();
            })
        .block();

    return new Result(exchange.getResponse().getStatusCode(), forwarded.get());
  }

  private MockServerHttpRequest.BaseBuilder<?> authorized(String path, String token) {
    return MockServerHttpRequest.get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
  }

  private ExchangeFunction usersResponds(String body) {
    return request -> {
      usersCalls.incrementAndGet();
      usersRequest.set(request);
      return Mono.just(
          ClientResponse.create(HttpStatus.OK)
              .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
              .body(body)
              .build());
    };
  }

  private String user(boolean active, String role) {
    String roleValue = role == null ? "null" : "\"" + role + "\"";
    return """
        {"data":{"user_id":"%s","username":"%s","active":%s,"role":%s,
        "is_delivery_staff":false}}
        """
        .formatted(USER_ID, USERNAME, active, roleValue);
  }

  private String validToken(String role) {
    return token(SECRET, USERNAME, role, Instant.now());
  }

  private String token(String secret, String username, String role, Instant issuedAt) {
    return token(secret, username, USER_ID.toString(), role, issuedAt);
  }

  private String token(
      String secret, String username, String userId, String role, Instant issuedAt) {
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
    var header = JwsHeader.with(MacAlgorithm.HS256).build();
    return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
  }

  private record Result(HttpStatusCode status, ServerWebExchange forwarded) {}
}
