package com.notfound.logistics.hub.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.notfound.logistics.hub.application.dto.hub.HubResult;
import com.notfound.logistics.hub.application.dto.hubroute.HubRouteResult;
import com.notfound.logistics.hub.application.dto.stock.StockResult;
import com.notfound.logistics.hub.application.service.hub.HubQueryService;
import com.notfound.logistics.hub.application.service.hubroute.HubRouteQueryService;
import com.notfound.logistics.hub.application.service.stock.StockCommandService;
import com.notfound.logistics.hub.application.service.stock.StockQueryService;
import com.notfound.logistics.hub.infrastructure.audit.HubAuditorAware;
import com.notfound.logistics.hub.presentation.controller.hub.HubController;
import com.notfound.logistics.hub.presentation.controller.hub.InternalHubController;
import com.notfound.logistics.hub.presentation.controller.hubroute.HubRouteController;
import com.notfound.logistics.hub.presentation.controller.hubroute.InternalHubRouteController;
import com.notfound.logistics.hub.presentation.controller.stock.InternalStockController;
import com.notfound.logistics.hub.presentation.controller.stock.InternalStockDeductionController;
import com.notfound.logistics.hub.presentation.controller.stock.InternalStockRestorationController;
import com.notfound.logistics.hub.presentation.controller.stock.StockController;
import com.notfound.logistics.hub.support.HubSecurityTest;
import com.notfound.logistics.hub.support.HubSecurityTestTokens;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest({
  HubController.class,
  InternalHubController.class,
  HubRouteController.class,
  InternalHubRouteController.class,
  StockController.class,
  InternalStockController.class,
  InternalStockDeductionController.class,
  InternalStockRestorationController.class,
  HubSecurityIntegrationTest.SecurityFixture.class
})
@HubSecurityTest
@Import({
  HubAuditorAware.class,
  HubSecurityIntegrationTest.ProtectedFixture.class,
  HubSecurityIntegrationTest.SecurityFixture.class
})
class HubSecurityIntegrationTest {
  private static final String HUB = "00000000-0000-0000-0000-000000000001";
  private static final String PRODUCT = "00000000-0000-0000-0000-000000000002";
  private static final String ADD = "/api/hubs/" + HUB + "/products/" + PRODUCT + "/stocks/add";
  @Autowired private MockMvc mvc;
  @MockitoBean private HubQueryService hubs;
  @MockitoBean private HubRouteQueryService routes;
  @MockitoBean private StockQueryService stocks;
  @MockitoBean private StockCommandService commands;

  @AfterEach
  void contextDoesNotLeakBetweenRequests() {
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    MDC.clear();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/hubs",
        "/api/hubs/" + HUB,
        "/api/hub-routes",
        "/api/hubs/" + HUB + "/stocks",
        "/api/products/" + PRODUCT + "/stocks",
        "/api/hubs/" + HUB + "/products/" + PRODUCT + "/stocks"
      })
  void allExternalReadsRequireAuthentication(String path) throws Exception {
    mvc.perform(get(path).header("X-Actor-Username", "admin").header("X-User-Role", "MASTER"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("WWW-Authenticate", "Bearer"))
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
        .andExpect(jsonPath("$.field_errors").isEmpty());
    verifyNoInteractions(hubs, routes, stocks, commands);
  }

  @Test
  void verifiedSubjectAndRoleDriveAuthenticationAndAuditDespiteForgedHeaders() throws Exception {
    MDC.put("traceId", "hub-security-trace");
    var result =
        mvc.perform(
                get("/api/hubs/security-fixture")
                    .header(
                        "Authorization", "Bearer " + HubSecurityTestTokens.token("alice", "USER"))
                    .header("X-Actor-Username", "admin")
                    .header("X-Actor-Id", "forged")
                    .header("X-Service-Token", HubSecurityTestTokens.ORDERS)
                    .header("X-User-Role", "MASTER"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.user_id").value("alice"))
            .andExpect(jsonPath("$.audit").value("alice"))
            .andExpect(jsonPath("$.authorities[0]").value("ROLE_USER"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andReturn();
    assertThat(result.getRequest().getSession(false)).isNull();
    mvc.perform(get("/api/hubs/security-fixture"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.trace_id").value("hub-security-trace"));
  }

  @Test
  void userCanReadProductAndHubStocks() throws Exception {
    UUID hub = UUID.fromString(HUB);
    UUID product = UUID.fromString(PRODUCT);
    when(stocks.getStocks(hub, null, 0, 20)).thenReturn(Page.empty(PageRequest.of(0, 20)));
    when(stocks.getStocks(null, product, 0, 20)).thenReturn(Page.empty(PageRequest.of(0, 20)));
    when(stocks.getStock(hub, product))
        .thenReturn(new StockResult(UUID.randomUUID(), hub, product, 0));
    for (String path :
        List.of(
            "/api/hubs/" + HUB + "/stocks",
            "/api/products/" + PRODUCT + "/stocks",
            "/api/hubs/" + HUB + "/products/" + PRODUCT + "/stocks")) {
      mvc.perform(
              get(path)
                  .header(
                      "Authorization", "Bearer " + HubSecurityTestTokens.token("alice", "USER")))
          .andExpect(status().isOk());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"/api/stocks", ADD})
  void userCannotRegisterOrAddStockEvenWithForgedMasterHeader(String path) throws Exception {
    mvc.perform(
            post(path)
                .header("Authorization", "Bearer " + HubSecurityTestTokens.token("alice", "USER"))
                .header("X-User-Role", "MASTER")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    verifyNoInteractions(commands);
  }

  @Test
  void methodDenialUsesCommon403RatherThanGlobal500() throws Exception {
    mvc.perform(
            get("/api/hubs/master-fixture")
                .header("Authorization", "Bearer " + HubSecurityTestTokens.token("alice", "USER")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    mvc.perform(
            get("/api/hubs/master-fixture")
                .header(
                    "Authorization", "Bearer " + HubSecurityTestTokens.token("admin", "MASTER")))
        .andExpect(status().isOk());
  }

  @ParameterizedTest
  @MethodSource("invalidTokens")
  void invalidTokensAreRejectedBeforeBusinessCode(String token) throws Exception {
    mvc.perform(get("/api/hubs").header("Authorization", "Bearer " + token))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    verifyNoInteractions(hubs);
  }

  static Stream<String> invalidTokens() {
    var claims = HubSecurityTestTokens.claims("alice", "USER");
    return Stream.of(
        "invalid",
        HubSecurityTestTokens.sign(
            HubSecurityTestTokens.claims("alice", "USER").build(),
            "another-test-only-signing-key-32-bytes"),
        HubSecurityTestTokens.sign(
            claims.issuer("another-service").build(), HubSecurityTestTokens.SECRET),
        HubSecurityTestTokens.sign(
            HubSecurityTestTokens.claims("alice", "USER")
                .issuedAt(Instant.now().minusSeconds(120))
                .expiresAt(Instant.now().minusSeconds(60))
                .build(),
            HubSecurityTestTokens.SECRET),
        HubSecurityTestTokens.sign(
            HubSecurityTestTokens.claims("alice", "USER")
                .notBefore(Instant.now().plusSeconds(60))
                .build(),
            HubSecurityTestTokens.SECRET),
        HubSecurityTestTokens.sign(
            HubSecurityTestTokens.claims("alice", "USER")
                .claims(values -> values.remove("exp"))
                .build(),
            HubSecurityTestTokens.SECRET),
        HubSecurityTestTokens.sign(
            HubSecurityTestTokens.claims("alice", "USER")
                .claims(values -> values.remove("sub"))
                .build(),
            HubSecurityTestTokens.SECRET),
        HubSecurityTestTokens.token("", "USER"),
        HubSecurityTestTokens.token(" ", "USER"),
        HubSecurityTestTokens.token("x".repeat(101), "USER"),
        HubSecurityTestTokens.token("alice", "ADMIN"),
        HubSecurityTestTokens.sign(
            HubSecurityTestTokens.claims("alice", "USER")
                .claims(values -> values.remove("role"))
                .build(),
            HubSecurityTestTokens.SECRET),
        HubSecurityTestTokens.sign(
            HubSecurityTestTokens.claims("alice", "USER").claim("role", 1).build(),
            HubSecurityTestTokens.SECRET),
        HubSecurityTestTokens.sign(
            HubSecurityTestTokens.claims("alice", "USER").claim("role", List.of("USER")).build(),
            HubSecurityTestTokens.SECRET));
  }

  @ParameterizedTest
  @ValueSource(strings = {"minimal", "without-iat", "without-typ"})
  void ordersJwtContractDoesNotRequireAdditionalHubClaims(String variant) throws Exception {
    var claims = HubSecurityTestTokens.claims("alice", "USER");
    if (variant.equals("without-iat")) claims.claims(values -> values.remove("iat"));
    var header = JwsHeader.with(MacAlgorithm.HS256);
    if (!variant.equals("without-typ")) header.type("JWT");
    String token =
        HubSecurityTestTokens.sign(claims.build(), HubSecurityTestTokens.SECRET, header.build());
    mvc.perform(get("/api/hubs/security-fixture").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.user_id").value("alice"))
        .andExpect(jsonPath("$.audit").value("alice"))
        .andExpect(jsonPath("$.authorities[0]").value("ROLE_USER"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/internal/hubs",
        "/internal/hubs/" + HUB,
        "/internal/hubs/" + HUB + "/stocks/" + PRODUCT,
        "/internal/hub-routes"
      })
  void userJwtAndActorHeaderDoNotAuthenticateInternalCalls(String path) throws Exception {
    mvc.perform(
            get(path)
                .header("Authorization", "Bearer " + HubSecurityTestTokens.token("admin", "MASTER"))
                .header("X-Actor-Username", "admin"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    verifyNoInteractions(hubs, routes, stocks, commands);
  }

  @Test
  void signupLookupUsesUsersServiceCredentialsWithoutInventingAnAuditActor() throws Exception {
    when(hubs.getHubsByName("서울")).thenReturn(List.of());
    mvc.perform(
            get("/internal/hubs")
                .param("name", "서울")
                .header("X-Service-Token", HubSecurityTestTokens.USERS))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").isEmpty());
    mvc.perform(
            get("/internal/hubs")
                .param("name", "서울")
                .header("X-Service-Token", HubSecurityTestTokens.ORDERS)
                .header("X-Actor-Username", "alice"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @ParameterizedTest
  @CsvSource({"users,401", "company,403", "delivery,403", "orders,200"})
  void onlyOrdersCanDeductAndMustProvideAnActor(String caller, int expected) throws Exception {
    String token =
        switch (caller) {
          case "users" -> HubSecurityTestTokens.USERS;
          case "company" -> HubSecurityTestTokens.COMPANY;
          case "delivery" -> HubSecurityTestTokens.DELIVERY;
          default -> HubSecurityTestTokens.ORDERS;
        };
    when(commands.deduct(UUID.fromString(HUB), UUID.fromString(PRODUCT), 1))
        .thenReturn(
            new StockResult(UUID.randomUUID(), UUID.fromString(HUB), UUID.fromString(PRODUCT), 9));
    var request =
        post("/internal/stocks/deductions")
            .header("X-Service-Token", token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(deductionBody());
    if (!caller.equals("users")) request.header("X-Actor-Username", "alice");
    mvc.perform(request).andExpect(status().is(expected));
    if (expected != 200) verifyNoInteractions(commands);
  }

  @ParameterizedTest
  @CsvSource({
    "USERS,names,200", "USERS,hub,200", "USERS,stocks,403", "USERS,routes,403",
    "COMPANY,names,403", "COMPANY,hub,200", "COMPANY,stocks,403", "COMPANY,routes,403",
    "ORDERS,names,403", "ORDERS,hub,403", "ORDERS,stocks,200", "ORDERS,routes,403",
    "DELIVERY,names,403", "DELIVERY,hub,403", "DELIVERY,stocks,403", "DELIVERY,routes,200"
  })
  void internalReadPermissionsAreScopedToTheCallingService(
      String caller, String resource, int expected) throws Exception {
    String token =
        switch (caller) {
          case "USERS" -> HubSecurityTestTokens.USERS;
          case "COMPANY" -> HubSecurityTestTokens.COMPANY;
          case "DELIVERY" -> HubSecurityTestTokens.DELIVERY;
          default -> HubSecurityTestTokens.ORDERS;
        };
    String path =
        switch (resource) {
          case "names" -> "/internal/hubs";
          case "hub" -> "/internal/hubs/" + HUB;
          case "stocks" -> "/internal/hubs/" + HUB + "/stocks/" + PRODUCT;
          default -> "/internal/hub-routes";
        };
    UUID hub = UUID.fromString(HUB);
    UUID product = UUID.fromString(PRODUCT);
    when(hubs.getHubsByName("서울")).thenReturn(List.of());
    when(hubs.getHub(hub)).thenReturn(new HubResult(hub, "서울", "서울 주소"));
    when(stocks.getStock(hub, product))
        .thenReturn(new StockResult(UUID.randomUUID(), hub, product, 0));
    when(routes.getRoute(hub, product))
        .thenReturn(new HubRouteResult(UUID.randomUUID(), hub, product, 1, BigDecimal.ONE));
    mvc.perform(
            get(path)
                .header("X-Service-Token", token)
                .header("X-Actor-Username", "alice")
                .param("name", "서울")
                .param("departure_hub_id", HUB)
                .param("arrival_hub_id", PRODUCT))
        .andExpect(status().is(expected));
    if (expected == 403) verifyNoInteractions(hubs, stocks, routes);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 100})
  void verifiedUsernamesAtAuditLengthBoundariesAreAccepted(int length) throws Exception {
    String username = "x".repeat(length);
    mvc.perform(
            get("/api/hubs/security-fixture")
                .header("Authorization", "Bearer " + HubSecurityTestTokens.token(username, "USER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.audit").value(username));
  }

  @ParameterizedTest
  @CsvSource({"USERS,hub", "COMPANY,hub", "ORDERS,stocks", "DELIVERY,routes"})
  void onlySignupNameLookupMayOmitOriginalActor(String caller, String resource) throws Exception {
    String token =
        switch (caller) {
          case "USERS" -> HubSecurityTestTokens.USERS;
          case "COMPANY" -> HubSecurityTestTokens.COMPANY;
          case "ORDERS" -> HubSecurityTestTokens.ORDERS;
          default -> HubSecurityTestTokens.DELIVERY;
        };
    String path =
        switch (resource) {
          case "hub" -> "/internal/hubs/" + HUB;
          case "stocks" -> "/internal/hubs/" + HUB + "/stocks/" + PRODUCT;
          default -> "/internal/hub-routes";
        };
    mvc.perform(get(path).header("X-Service-Token", token).header("X-Actor-Id", HUB))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    verifyNoInteractions(hubs, stocks, routes);
  }

  @ParameterizedTest
  @ValueSource(strings = {"absent", "expired", "invalid"})
  void serviceAuthenticationAllowsRecoveryWithoutAValidUserJwt(String bearer) throws Exception {
    when(commands.deduct(UUID.fromString(HUB), UUID.fromString(PRODUCT), 1))
        .thenReturn(
            new StockResult(UUID.randomUUID(), UUID.fromString(HUB), UUID.fromString(PRODUCT), 9));
    var request =
        post("/internal/stocks/deductions")
            .header("X-Service-Token", HubSecurityTestTokens.ORDERS)
            .header("X-Actor-Username", "persisted-requester")
            .contentType(MediaType.APPLICATION_JSON)
            .content(deductionBody());
    if (bearer.equals("expired")) {
      String token =
          HubSecurityTestTokens.sign(
              HubSecurityTestTokens.claims("alice", "USER")
                  .issuedAt(Instant.now().minusSeconds(120))
                  .expiresAt(Instant.now().minusSeconds(60))
                  .build(),
              HubSecurityTestTokens.SECRET);
      request.header("Authorization", "Bearer " + token);
    } else if (bearer.equals("invalid")) {
      request.header("Authorization", "Bearer invalid");
    }
    mvc.perform(request).andExpect(status().isOk());
  }

  @Test
  void additionalClaimsDoNotOverrideSubjectOrGrantMasterPermissions() throws Exception {
    String token =
        HubSecurityTestTokens.sign(
            HubSecurityTestTokens.claims("alice", "USER")
                .claim("username", "admin")
                .claim("is_delivery_staff", true)
                .build(),
            HubSecurityTestTokens.SECRET);
    mvc.perform(get("/api/hubs/security-fixture").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.user_id").value("alice"))
        .andExpect(jsonPath("$.audit").value("alice"));
    mvc.perform(post("/api/stocks").header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden());
    verifyNoInteractions(commands);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "unknown-service-token"})
  void invalidServiceTokensCannotWrite(String token) throws Exception {
    mvc.perform(
            post("/internal/stocks/deductions")
                .header("X-Service-Token", token)
                .header("X-Actor-Username", "alice")
                .contentType(MediaType.APPLICATION_JSON)
                .content(deductionBody()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    verifyNoInteractions(commands);
  }

  @Test
  void duplicateHeadersAndInvalidAuditActorsAreRejected() throws Exception {
    mvc.perform(
            post("/internal/stocks/deductions")
                .header(
                    "X-Service-Token", HubSecurityTestTokens.ORDERS, HubSecurityTestTokens.ORDERS)
                .header("X-Actor-Username", "alice"))
        .andExpect(status().isUnauthorized());
    for (String actor : List.of("", " ", "x".repeat(101))) {
      mvc.perform(
              post("/internal/stocks/deductions")
                  .header("X-Service-Token", HubSecurityTestTokens.ORDERS)
                  .header("X-Actor-Username", actor))
          .andExpect(status().isUnauthorized());
    }
    mvc.perform(
            post("/internal/stocks/deductions")
                .header("X-Service-Token", HubSecurityTestTokens.ORDERS)
                .header("X-Actor-Username", "alice", "admin"))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(commands);
  }

  @Test
  void infrastructureProbeRemainsPublicButServiceTokenDoesNotGrantExternalAccess()
      throws Exception {
    mvc.perform(get("/internal/infra/ping")).andExpect(status().isOk());
    mvc.perform(
            post("/api/stocks")
                .header("X-Service-Token", HubSecurityTestTokens.ORDERS)
                .header("X-Actor-Username", "admin"))
        .andExpect(status().isUnauthorized());
  }

  private String deductionBody() {
    return "{\"order_id\":\""
        + UUID.randomUUID()
        + "\",\"hub_id\":\""
        + HUB
        + "\",\"product_id\":\""
        + PRODUCT
        + "\",\"quantity\":1}";
  }

  @Nested
  @TestPropertySource(properties = "hub.jwt.secret=")
  class WithoutJwtSecret {
    @Autowired private MockMvc unconfiguredMvc;

    @Test
    void missingSecretRejectsJwtAndDoesNotDisableServiceAuthentication() throws Exception {
      unconfiguredMvc
          .perform(
              get("/api/hubs")
                  .header(
                      "Authorization", "Bearer " + HubSecurityTestTokens.token("alice", "USER")))
          .andExpect(status().isUnauthorized());
      unconfiguredMvc.perform(get("/internal/infra/ping")).andExpect(status().isOk());
    }
  }

  @Nested
  @TestPropertySource(properties = "hub.security.internal.orders-token=")
  class WithoutOrdersToken {
    @Autowired private MockMvc unconfiguredMvc;

    @Test
    void missingServiceConfigurationRejectsInternalCalls() throws Exception {
      unconfiguredMvc
          .perform(
              post("/internal/stocks/deductions")
                  .header("X-Service-Token", HubSecurityTestTokens.ORDERS)
                  .header("X-Actor-Username", "alice"))
          .andExpect(status().isUnauthorized());
    }
  }

  @ParameterizedTest
  @CsvSource({"users,401", "company,403", "delivery,403", "orders,200"})
  void onlyOrdersCanRestoreStock(String caller, int expected) throws Exception {

    String token =
        switch (caller) {
          case "users" -> HubSecurityTestTokens.USERS;
          case "company" -> HubSecurityTestTokens.COMPANY;
          case "delivery" -> HubSecurityTestTokens.DELIVERY;
          default -> HubSecurityTestTokens.ORDERS;
        };

    UUID hubId = UUID.fromString(HUB);
    UUID productId = UUID.fromString(PRODUCT);

    when(commands.restore(hubId, productId, 10))
        .thenReturn(new StockResult(UUID.randomUUID(), hubId, productId, 100));

    var request =
        post("/internal/stocks/restorations")
            .header("X-Service-Token", token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(restorationBody());

    if (!caller.equals("users")) {
      request.header("X-Actor-Username", "alice");
    }

    mvc.perform(request).andExpect(status().is(expected));

    if (expected == 200) {
      verify(commands).restore(hubId, productId, 10);
    } else {
      verifyNoInteractions(commands);
    }
  }

  @Test
  void restoreRequiresOrdersServiceAuthentication() throws Exception {

    mvc.perform(
            post("/internal/stocks/restorations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(restorationBody()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

    mvc.perform(
            post("/internal/stocks/restorations")
                .header("Authorization", "Bearer " + HubSecurityTestTokens.token("admin", "MASTER"))
                .header("X-Actor-Username", "admin")
                .contentType(MediaType.APPLICATION_JSON)
                .content(restorationBody()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

    verifyNoInteractions(commands);
  }

  @Test
  void restoreRequiresOriginalAuditActor() throws Exception {

    mvc.perform(
            post("/internal/stocks/restorations")
                .header("X-Service-Token", HubSecurityTestTokens.ORDERS)
                .contentType(MediaType.APPLICATION_JSON)
                .content(restorationBody()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

    verifyNoInteractions(commands);
  }

  private String restorationBody() {
    return "{\"order_id\":\""
        + UUID.randomUUID()
        + "\",\"hub_id\":\""
        + HUB
        + "\",\"product_id\":\""
        + PRODUCT
        + "\",\"quantity\":10}";
  }

  static class ProtectedFixture {
    @PreAuthorize("hasRole('MASTER')")
    public String masterOnly() {
      return "allowed";
    }
  }

  @RestController
  static class SecurityFixture {
    private final HubAuditorAware auditor;
    private final ProtectedFixture protectedFixture;

    SecurityFixture(HubAuditorAware auditor, ProtectedFixture protectedFixture) {
      this.auditor = auditor;
      this.protectedFixture = protectedFixture;
    }

    @GetMapping("/api/hubs/security-fixture")
    Map<String, Object> actor(Authentication authentication) {
      return Map.of(
          "user_id",
          authentication.getName(),
          "audit",
          auditor.getCurrentAuditor().orElseThrow(),
          "authorities",
          authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList());
    }

    @GetMapping("/api/hubs/master-fixture")
    String master() {
      return protectedFixture.masterOnly();
    }

    @GetMapping("/internal/infra/ping")
    String probe() {
      return "UP";
    }
  }
}
