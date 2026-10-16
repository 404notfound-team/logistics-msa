package com.notfound.logistics.users.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.notfound.logistics.users.infrastructure.security.ServiceAuthentication;
import feign.RequestTemplate;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class InternalServiceFeignConfigurationTest {
  private final InternalServiceFeignConfiguration configuration =
      new InternalServiceFeignConfiguration();

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void signupLookupSendsServiceTokenWithoutActor() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

    RequestTemplate template = apply();

    assertThat(template.headers().get("X-Service-Token"))
        .containsExactly("test-only-users-service-token");
    assertThat(template.headers()).doesNotContainKey("X-Actor-Username");
  }

  @Test
  void authenticatedUserIsForwardedAsActor() {
    Jwt jwt =
        Jwt.withTokenValue("token")
            .header("alg", "HS256")
            .subject("11111111-1111-1111-1111-111111111111")
            .claim("username", "master01")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build();
    SecurityContextHolder.getContext()
        .setAuthentication(new JwtAuthenticationToken(jwt, null, "master01"));

    assertThat(apply().headers().get("X-Actor-Username")).containsExactly("master01");
  }

  @Test
  void actorReceivedFromAnotherServiceIsForwarded() {
    SecurityContextHolder.getContext()
        .setAuthentication(new ServiceAuthentication("orders", "master01"));

    assertThat(apply().headers().get("X-Actor-Username")).containsExactly("master01");
  }

  @Test
  void refusesToStartWithoutServiceToken() {
    assertThatThrownBy(() -> configuration.internalServiceAuthInterceptor(" "))
        .isInstanceOf(IllegalStateException.class);
  }

  private RequestTemplate apply() {
    RequestTemplate template = new RequestTemplate();
    configuration.internalServiceAuthInterceptor("test-only-users-service-token").apply(template);
    return template;
  }
}
