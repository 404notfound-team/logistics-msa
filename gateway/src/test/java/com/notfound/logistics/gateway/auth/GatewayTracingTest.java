package com.notfound.logistics.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/** tracing을 켠 Gateway가 Users 인가 조회와 오류 응답을 원 요청의 trace에 묶는지 확인합니다. */
@ActiveProfiles("test")
@AutoConfigureObservability(metrics = false)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "JWT_SECRET=" + GatewaySecurityTest.SECRET,
      "GATEWAY_SERVICE_TOKEN=test-only-gateway-service-token",
      "eureka.client.enabled=false",
      "spring.cloud.service-registry.auto-registration.enabled=false",
      "management.zipkin.tracing.export.enabled=false"
    })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GatewayTracingTest {
  private static final String USER_ID = "11111111-1111-1111-1111-111111111111";
  private static final String AUTHORIZATION_PATH = "/internal/users/" + USER_ID + "/authorization";

  /** Users 인가 조회가 받은 traceparent입니다. */
  private static final Map<String, String> RECEIVED = new ConcurrentHashMap<>();

  private static volatile boolean usersFails;
  private static final DisposableServer USERS = startUsers();

  @LocalServerPort private int port;
  private WebTestClient client;

  @DynamicPropertySource
  static void serviceLocations(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.cloud.discovery.client.simple.instances.users[0].uri",
        () -> "http://127.0.0.1:" + USERS.port());
  }

  @BeforeEach
  void setUp() {
    client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
    RECEIVED.clear();
    usersFails = false;
  }

  @AfterAll
  static void stopUsers() {
    USERS.disposeNow();
  }

  @Test
  void consultsUsersWithinTheIncomingTrace() {
    String traceId = newTraceId();

    client
        .get()
        .uri("/api/users/me")
        .header("traceparent", traceparent(traceId))
        .headers(headers -> headers.setBearerAuth(token()))
        .exchange();

    assertThat(RECEIVED.get(AUTHORIZATION_PATH)).startsWith("00-" + traceId + "-");
  }

  @Test
  void reportsTraceIdWhenRejectingMissingToken() {
    String traceId = newTraceId();

    client
        .get()
        .uri("/api/orders")
        .header("traceparent", traceparent(traceId))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UNAUTHORIZED)
        .expectBody()
        .jsonPath("$.trace_id")
        .isEqualTo(traceId);
  }

  @Test
  void reportsTraceIdWhenUsersIsUnavailable() {
    usersFails = true;
    String traceId = newTraceId();

    client
        .get()
        .uri("/api/orders")
        .header("traceparent", traceparent(traceId))
        .headers(headers -> headers.setBearerAuth(token()))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        .expectBody()
        .jsonPath("$.trace_id")
        .isEqualTo(traceId);
  }

  private static String newTraceId() {
    byte[] bytes = new byte[16];
    ThreadLocalRandom.current().nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }

  private static String traceparent(String traceId) {
    return "00-" + traceId + "-00f067aa0ba902b7-01";
  }

  private static String token() {
    var key =
        new SecretKeySpec(
            GatewaySecurityTest.SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    var encoder = new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(key));
    Instant now = Instant.now();
    var claims =
        JwtClaimsSet.builder()
            .issuer("logistics-users")
            .subject("user01")
            .issuedAt(now)
            .expiresAt(now.plusSeconds(900))
            .claim("user_id", USER_ID)
            .claim("role", "USER")
            .claim("is_delivery_staff", false)
            .claim("token_version", 0L)
            .build();
    var header = JwsHeader.with(MacAlgorithm.HS256).build();
    return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
  }

  private static DisposableServer startUsers() {
    return HttpServer.create()
        .host("127.0.0.1")
        .port(0)
        .handle(
            (request, response) -> {
              String traceparent = request.requestHeaders().get("traceparent");
              if (traceparent != null) {
                RECEIVED.put(request.uri(), traceparent);
              }
              if (usersFails) {
                return response.status(500).send();
              }
              String body =
                  request.uri().equals(AUTHORIZATION_PATH)
                      ? "{\"data\":{\"user_id\":\"%s\",\"username\":\"user01\",\"active\":true,"
                              .formatted(USER_ID)
                          + "\"role\":\"USER\",\"is_delivery_staff\":false,\"token_version\":0}}"
                      : "{\"data\":{}}";
              return response
                  .header("Content-Type", "application/json")
                  .sendString(Mono.just(body));
            })
        .bindNow();
  }
}
