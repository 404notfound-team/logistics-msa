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
  void jwtSubjectIdentifiesUserWhileSeparateClaimIdentifiesAuditActor() {
    String userId = "4c7c4c0f-94fb-40d2-b467-5ccaecc9de3e";
    var jwt =
        Jwt.withTokenValue("test-only-token")
            .header("alg", "HS256")
            .subject(userId)
            .claim("username", "alice")
            .build();
    var authentication = new JwtAuthenticationToken(jwt, List.of());
    SecurityContextHolder.getContext().setAuthentication(authentication);
    assertThat(authentication.getName()).isEqualTo(userId);
    assertThat(new HubAuditorAware().getCurrentAuditor()).contains("alice");
  }

  @Test
  void missingJwtUsernameNeverFallsBackToUuidSubject() {
    var jwt =
        Jwt.withTokenValue("test-only-token")
            .header("alg", "HS256")
            .subject("4c7c4c0f-94fb-40d2-b467-5ccaecc9de3e")
            .build();
    SecurityContextHolder.getContext()
        .setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    assertThatThrownBy(() -> new HubAuditorAware().getCurrentAuditor())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("username");
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
