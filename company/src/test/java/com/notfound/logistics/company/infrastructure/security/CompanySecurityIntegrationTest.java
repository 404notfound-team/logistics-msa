package com.notfound.logistics.company.infrastructure.security;

import static com.notfound.logistics.company.support.CompanySecurityTestTokens.bearer;
import static com.notfound.logistics.company.support.CompanySecurityTestTokens.claims;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.notfound.logistics.company.support.CompanySecurityTestTokens;
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
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

// 실제 서명한 JWT로 공개 API 인증 규칙을, X-Service-Token으로 내부 API 서비스 인증 규칙을 확인한다. 업무 컨트롤러 대신 인증 결과만 돌려주는
// fixture를 쓴다.
@WebMvcTest(
    properties = {
      "company.jwt.secret=" + CompanySecurityTestTokens.SECRET,
      "company.security.internal.orders-token=" + CompanySecurityTestTokens.ORDERS_TOKEN
    })
@ContextConfiguration(
    classes = {
      CompanySecurityConfiguration.class,
      CompanySecurityIntegrationTest.FixtureController.class
    })
class CompanySecurityIntegrationTest {
  @Autowired private MockMvc mvc;

  @Test
  void signedSubjectAndRoleBecomeTheAuthenticationWithoutSession() throws Exception {
    var result =
        mvc.perform(
                post("/api/companies/security-fixture")
                    .header(HttpHeaders.AUTHORIZATION, bearer(claims().build()))
                    // 요청 헤더로 사용자·역할을 바꿀 수 없다.
                    .header("X-Actor-Username", "admin")
                    .header("X-User-Role", "MASTER"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value(CompanySecurityTestTokens.USERNAME))
            .andExpect(jsonPath("$.authorities[0]").value("ROLE_USER"))
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
            .andReturn();
    assertThat(result.getRequest().getSession(false)).isNull();
  }

  @Test
  void masterRoleIsMappedToRoleMaster() throws Exception {
    mvc.perform(
            get("/api/companies/security-fixture")
                .header(
                    HttpHeaders.AUTHORIZATION,
                    bearer(claims().subject("admin").claim("role", "MASTER").build())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value("admin"))
        .andExpect(jsonPath("$.authorities[0]").value("ROLE_MASTER"));
  }

  @Test
  void missingTokenReturnsUnauthorizedInCommonFormat() throws Exception {
    mvc.perform(get("/api/companies/security-fixture"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
        .andExpect(jsonPath("$.field_errors").isEmpty());
  }

  @Test
  void malformedTokenReturnsUnauthorized() throws Exception {
    mvc.perform(
            get("/api/companies/security-fixture")
                .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
  }

  @ParameterizedTest
  @MethodSource("invalidClaims")
  void rejectsInvalidSignedClaims(JwtClaimsSet claims) throws Exception {
    mvc.perform(
            get("/api/companies/security-fixture")
                .header(HttpHeaders.AUTHORIZATION, bearer(claims)))
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
  void pathsOutsideApiAreNotAuthenticatedByThisChain() throws Exception {
    mvc.perform(get("/public/security-fixture").header(HttpHeaders.AUTHORIZATION, "Bearer invalid"))
        .andExpect(status().isOk());
  }

  @Nested
  class InternalApi {
    private static final String PATH = "/internal/companies/security-fixture";

    @Test
    void ordersTokenAuthenticatesTheActorAsOrdersService() throws Exception {
      var result =
          mvc.perform(
                  get(PATH)
                      .header("X-Service-Token", CompanySecurityTestTokens.ORDERS_TOKEN)
                      .header("X-Actor-Username", "orderer"))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.username").value("orderer"))
              .andExpect(jsonPath("$.authorities[0]").value("SERVICE_ORDERS"))
              .andReturn();
      assertThat(result.getRequest().getSession(false)).isNull();
    }

    @Test
    void productLookupIsAlsoAllowedForOrders() throws Exception {
      mvc.perform(
              get("/internal/products/security-fixture")
                  .header("X-Service-Token", CompanySecurityTestTokens.ORDERS_TOKEN)
                  .header("X-Actor-Username", "orderer"))
          .andExpect(status().isOk());
    }

    @Test
    void missingServiceTokenReturnsUnauthorizedWithoutBearerChallenge() throws Exception {
      // 사용자 JWT만으로는 내부 API를 호출할 수 없다.
      mvc.perform(
              get(PATH)
                  .header(HttpHeaders.AUTHORIZATION, bearer(claims().build()))
                  .header("X-Actor-Username", "orderer"))
          .andExpect(status().isUnauthorized())
          .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
          .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void wrongServiceTokenReturnsUnauthorized() throws Exception {
      mvc.perform(
              get(PATH)
                  .header("X-Service-Token", "another-service-token-value")
                  .header("X-Actor-Username", "orderer"))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void missingOrAmbiguousHeadersReturnUnauthorized() throws Exception {
      mvc.perform(get(PATH).header("X-Service-Token", CompanySecurityTestTokens.ORDERS_TOKEN))
          .andExpect(status().isUnauthorized());
      mvc.perform(
              get(PATH)
                  .header("X-Service-Token", CompanySecurityTestTokens.ORDERS_TOKEN)
                  .header("X-Service-Token", CompanySecurityTestTokens.ORDERS_TOKEN)
                  .header("X-Actor-Username", "orderer"))
          .andExpect(status().isUnauthorized());
      mvc.perform(
              get(PATH)
                  .header("X-Service-Token", CompanySecurityTestTokens.ORDERS_TOKEN)
                  .header("X-Actor-Username", "x".repeat(101)))
          .andExpect(status().isUnauthorized());
    }

    @Test
    void methodsNotGrantedToOrdersAreForbidden() throws Exception {
      mvc.perform(
              post(PATH)
                  .header("X-Service-Token", CompanySecurityTestTokens.ORDERS_TOKEN)
                  .header("X-Actor-Username", "orderer"))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
  }

  @Nested
  @TestPropertySource(properties = "company.jwt.secret=")
  class WithoutSecret {
    @Autowired private MockMvc unconfiguredMvc;

    @Test
    void refusesEveryTokenWhileOtherPathsRemainAvailable() throws Exception {
      unconfiguredMvc
          .perform(
              get("/api/companies/security-fixture")
                  .header(HttpHeaders.AUTHORIZATION, bearer(claims().build())))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
      unconfiguredMvc.perform(get("/public/security-fixture")).andExpect(status().isOk());
    }
  }

  @RestController
  static class FixtureController {
    @GetMapping("/api/companies/security-fixture")
    Map<String, Object> getActor(Authentication authentication) {
      return Map.of(
          "username", authentication.getName(),
          "authorities",
              authentication.getAuthorities().stream()
                  .map(GrantedAuthority::getAuthority)
                  .toList());
    }

    @PostMapping("/api/companies/security-fixture")
    Map<String, Object> postActor(Authentication authentication) {
      return getActor(authentication);
    }

    @GetMapping({"/internal/companies/security-fixture", "/internal/products/security-fixture"})
    Map<String, Object> getService(Authentication authentication) {
      return getActor(authentication);
    }

    @PostMapping("/internal/companies/security-fixture")
    Map<String, Object> postService(Authentication authentication) {
      return getActor(authentication);
    }

    @GetMapping("/public/security-fixture")
    String infrastructure() {
      return "available";
    }
  }
}
