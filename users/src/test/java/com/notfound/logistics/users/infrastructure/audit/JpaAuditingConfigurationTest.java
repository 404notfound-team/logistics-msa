package com.notfound.logistics.users.infrastructure.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.notfound.logistics.users.infrastructure.security.ServiceAuthentication;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class JpaAuditingConfigurationTest {
  private static final String SIGNUP_ACTOR = "signup-service";

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void usesJwtSubjectOfAuthenticatedUser() {
    authenticate(jwt("master01"));

    assertThat(JpaAuditingConfiguration.currentActor(SIGNUP_ACTOR)).isEqualTo("master01");
  }

  @Test
  void usesActorForwardedWithServiceAuthentication() {
    authenticate(new ServiceAuthentication("orders", "master01"));

    assertThat(JpaAuditingConfiguration.currentActor(SIGNUP_ACTOR)).isEqualTo("master01");
  }

  @Test
  void usesSignupActorOnlyInsideSignupScope() {
    assertThat(SignupAuditScope.run(() -> JpaAuditingConfiguration.currentActor(SIGNUP_ACTOR)))
        .isEqualTo(SIGNUP_ACTOR);
    assertThatThrownBy(() -> JpaAuditingConfiguration.currentActor(SIGNUP_ACTOR))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void refusesAnonymousOrActorlessWrites() {
    authenticate(
        new AnonymousAuthenticationToken(
            "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
    assertThatThrownBy(() -> JpaAuditingConfiguration.currentActor(SIGNUP_ACTOR))
        .isInstanceOf(IllegalStateException.class);

    authenticate(new ServiceAuthentication("orders", null));
    assertThatThrownBy(() -> JpaAuditingConfiguration.currentActor(SIGNUP_ACTOR))
        .isInstanceOf(IllegalStateException.class);
  }

  private void authenticate(Authentication authentication) {
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  private JwtAuthenticationToken jwt(String subject) {
    Instant now = Instant.now();
    Jwt jwt =
        Jwt.withTokenValue("token")
            .header("alg", "HS256")
            .subject(subject)
            .issuedAt(now)
            .expiresAt(now.plusSeconds(60))
            .build();
    return new JwtAuthenticationToken(jwt, List.of());
  }
}
