package com.notfound.logistics.hub.infrastructure.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class HubAuditorAwareTest {
  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void readsVerifiedUsername() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken("stock-tester", null, List.of()));
    assertThat(new HubAuditorAware().getCurrentAuditor()).contains("stock-tester");
  }

  @Test
  void jwtSubjectIsAuditUsernameEvenWhenSeparateClaimDisagrees() {
    var jwt =
        Jwt.withTokenValue("test-only-token")
            .header("alg", "HS256")
            .subject("alice")
            .claim("username", "forged-admin")
            .build();
    var authentication = new JwtAuthenticationToken(jwt, List.of());
    SecurityContextHolder.getContext().setAuthentication(authentication);
    assertThat(authentication.getName()).isEqualTo("alice");
    assertThat(new HubAuditorAware().getCurrentAuditor()).contains("alice");
  }

  @Test
  void jwtSubjectDoesNotRequireSeparateUsernameClaim() {
    var jwt = Jwt.withTokenValue("test-only-token").header("alg", "HS256").subject("alice").build();
    SecurityContextHolder.getContext()
        .setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    assertThat(new HubAuditorAware().getCurrentAuditor()).contains("alice");
  }

  @Test
  void rejectsUnauthenticatedAndAnonymousActors() {
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken("actor", "unverified"));
    assertThatThrownBy(() -> new HubAuditorAware().getCurrentAuditor())
        .isInstanceOf(IllegalStateException.class);
    SecurityContextHolder.getContext()
        .setAuthentication(
            new AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
    assertThatThrownBy(() -> new HubAuditorAware().getCurrentAuditor())
        .isInstanceOf(IllegalStateException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        " ",
        "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvw"
      })
  void rejectsInvalidUsername(String username) {
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(username, null, List.of()));
    assertThatThrownBy(() -> new HubAuditorAware().getCurrentAuditor())
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void rejectsMissingAuthenticatedActor() {
    HubAuditorAware auditorAware = new HubAuditorAware();

    assertThatThrownBy(auditorAware::getCurrentAuditor)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("감사 주체");
  }
}
