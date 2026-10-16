package com.notfound.logistics.hub.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

class HubInternalApiAuthorizationManagerTest {
  @Test
  void configuredMethodAndPathAreRequiredAndOtherCallersAreDenied() {
    var properties = properties("GET /internal/hubs/*");
    var manager = new HubInternalApiAuthorizationManager(properties);
    var caller =
        UsernamePasswordAuthenticationToken.authenticated(
            "actor", null, List.of(new SimpleGrantedAuthority("SERVICE_COMPANY")));
    assertThat(manager.check(() -> caller, context("GET", "/internal/hubs/123")).isGranted())
        .isTrue();
    assertThat(manager.check(() -> caller, context("POST", "/internal/hubs/123")).isGranted())
        .isFalse();
    assertThat(
            manager
                .check(() -> caller, context("GET", "/internal/hubs/123/stocks/456"))
                .isGranted())
        .isFalse();
    assertThat(manager.check(() -> null, context("GET", "/internal/hubs/123")).isGranted())
        .isFalse();
    var unrelated =
        UsernamePasswordAuthenticationToken.authenticated(
            "actor", null, List.of(new SimpleGrantedAuthority("SERVICE_ORDERS")));
    assertThat(manager.check(() -> unrelated, context("GET", "/internal/hubs/123")).isGranted())
        .isFalse();
  }

  @Test
  void emptyAllowlistDeniesEverythingAndInvalidConfigurationFailsStartup() {
    var caller =
        UsernamePasswordAuthenticationToken.authenticated(
            "actor", null, List.of(new SimpleGrantedAuthority("SERVICE_COMPANY")));
    assertThat(
            new HubInternalApiAuthorizationManager(properties())
                .check(() -> caller, context("GET", "/internal/hubs/123"))
                .isGranted())
        .isFalse();
    assertThatThrownBy(() -> new HubInternalApiAuthorizationManager(properties("GET /api/hubs")))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new HubInternalApiAuthorizationManager(properties("/internal/hubs")))
        .isInstanceOf(IllegalStateException.class);
  }

  private HubInternalAuthProperties properties(String... allowed) {
    return new HubInternalAuthProperties(
        Map.of(
            "company",
            new HubInternalAuthProperties.Client("company-test-token", List.of(allowed))));
  }

  private RequestAuthorizationContext context(String method, String uri) {
    return new RequestAuthorizationContext(new MockHttpServletRequest(method, uri));
  }
}
