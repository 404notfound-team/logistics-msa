package com.notfound.logistics.hub.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.notfound.logistics.hub.infrastructure.audit.HubAuditorAware;
import feign.RequestTemplate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class CompanyFeignConfigurationTest {
  private final CompanyFeignConfiguration configuration = new CompanyFeignConfiguration();
  private final HubAuditorAware auditor = new HubAuditorAware();

  @AfterEach
  void clearAuthentication() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void missingOutboundTokenFailsConfiguration() {
    assertThatThrownBy(() -> configuration.companyRequestHeaders(auditor, " "))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("HUB_SERVICE_TOKEN");
  }

  @Test
  void sendsOwnCredentialAndVerifiedActorWithoutUserJwt() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(
                "verified-user", null, List.of(new SimpleGrantedAuthority("ROLE_MASTER"))));
    var request = new RequestTemplate();
    configuration.companyRequestHeaders(auditor, "hub-service-test-token").apply(request);
    assertThat(request.headers().get("X-Service-Token")).containsExactly("hub-service-test-token");
    assertThat(request.headers().get("X-Actor-Username")).containsExactly("verified-user");
    assertThat(request.headers()).doesNotContainKey("Authorization");
  }

  @Test
  void missingVerifiedActorCannotSendRequest() {
    assertThatThrownBy(
            () ->
                configuration
                    .companyRequestHeaders(auditor, "hub-service-test-token")
                    .apply(new RequestTemplate()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void invalidTimeoutsAreRejected() {
    assertThatThrownBy(() -> configuration.companyRequestOptions(0, 100))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> configuration.companyRequestOptions(100, -1))
        .isInstanceOf(IllegalStateException.class);
  }
}
