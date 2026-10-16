package com.notfound.logistics.orders.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.notfound.logistics.orders.support.LocalOrderStub;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(properties = "orders.jwt.secret=" + LocalOrderStub.SECRET)
@ContextConfiguration(
    classes = {
      OrderSecurityConfiguration.class,
      OrderSecurityIntegrationTest.FixtureController.class
    })
class OrderSecurityIntegrationTest {
  @Autowired private MockMvc mvc;

  @Test
  void bearerAuthenticationUsesTheSignedSubjectAndRoleWithoutCreatingASession() throws Exception {
    var result =
        mvc.perform(
                post("/api/orders/security-fixture")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(claims().build()))
                    .header("X-Actor-Username", "admin")
                    .header("X-Actor-Id", "forged")
                    .header("X-User-Role", "MASTER"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value("alice"))
            .andExpect(jsonPath("$.authorities[0]").value("ROLE_USER"))
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
            .andReturn();
    assertThat(result.getRequest().getSession(false)).isNull();
    mvc.perform(get("/api/orders/security-fixture"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
        .andExpect(jsonPath("$.field_errors").isEmpty());
  }

  @Test
  void masterRoleIsAvailableToTheExistingOrderAuthorization() throws Exception {
    mvc.perform(
            get("/api/orders/security-fixture")
                .header(
                    HttpHeaders.AUTHORIZATION,
                    "Bearer " + token(claims().subject("admin").claim("role", "MASTER").build())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value("admin"))
        .andExpect(jsonPath("$.authorities[0]").value("ROLE_MASTER"));
  }

  @ParameterizedTest
  @MethodSource("invalidClaims")
  void rejectsInvalidSignedClaims(JwtClaimsSet claims) throws Exception {
    mvc.perform(
            get("/api/orders/security-fixture")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(claims)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
  }

  static Stream<JwtClaimsSet> invalidClaims() {
    return Stream.of(
        claims().issuer("another-service").build(),
        claims()
            .issuedAt(Instant.now().minusSeconds(120))
            .expiresAt(Instant.now().minusSeconds(60))
            .build(),
        claims().notBefore(Instant.now().plusSeconds(60)).build(),
        claims().claims(values -> values.remove("exp")).build(),
        claims().claims(values -> values.remove("sub")).build(),
        claims().subject(" ").build(),
        claims().subject("x".repeat(101)).build(),
        claims().claims(values -> values.remove("role")).build(),
        claims().claim("role", "ADMIN").build());
  }

  @Test
  void publicInfrastructurePathsKeepTheirExistingAuthenticationBehavior() throws Exception {
    mvc.perform(get("/public/security-fixture").header(HttpHeaders.AUTHORIZATION, "Bearer invalid"))
        .andExpect(status().isOk());
  }

  private static JwtClaimsSet.Builder claims() {
    return JwtClaimsSet.builder()
        .issuer("logistics-users")
        .subject("alice")
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(300))
        .claim("role", "USER");
  }

  private static String token(JwtClaimsSet claims) {
    var encoder =
        new NimbusJwtEncoder(
            new ImmutableSecret<>(LocalOrderStub.SECRET.getBytes(StandardCharsets.UTF_8)));
    return encoder
        .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
        .getTokenValue();
  }

  @Nested
  @TestPropertySource(properties = "orders.jwt.secret=")
  class WithoutSecret {
    @Autowired private MockMvc unconfiguredMvc;

    @Test
    void refusesBearerAuthenticationWhilePublicInfrastructureRemainsAvailable() throws Exception {
      unconfiguredMvc
          .perform(
              get("/api/orders/security-fixture")
                  .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(claims().build())))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
      unconfiguredMvc.perform(get("/public/security-fixture")).andExpect(status().isOk());
    }
  }

  @RestController
  static class FixtureController {
    @GetMapping("/api/orders/security-fixture")
    Map<String, Object> getActor(Authentication authentication) {
      return Map.of(
          "username", authentication.getName(),
          "authorities",
              authentication.getAuthorities().stream()
                  .map(GrantedAuthority::getAuthority)
                  .toList());
    }

    @PostMapping("/api/orders/security-fixture")
    Map<String, Object> postActor(Authentication authentication) {
      return getActor(authentication);
    }

    @GetMapping("/public/security-fixture")
    String infrastructure() {
      return "available";
    }
  }
}
