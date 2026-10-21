package com.notfound.logistics.company.infrastructure.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class CompanyAuditorAwareTest {
  private final CompanyAuditorAware auditorAware = new CompanyAuditorAware();

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void returnsAuthenticatedUsername() {
    authenticate("alice");

    assertThat(auditorAware.getCurrentAuditor()).contains("alice");
  }

  @Test
  void failsWithoutAuthentication() {
    // 공통 계약: 인증 주체가 없으면 임의 작업자로 대체하지 않고 실패한다.
    assertThatThrownBy(auditorAware::getCurrentAuditor).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void failsForAnonymousAuthentication() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

    assertThatThrownBy(auditorAware::getCurrentAuditor).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void failsForBlankOrTooLongUsername() {
    authenticate(" ");
    assertThatThrownBy(auditorAware::getCurrentAuditor).isInstanceOf(IllegalStateException.class);

    authenticate("x".repeat(101));
    assertThatThrownBy(auditorAware::getCurrentAuditor).isInstanceOf(IllegalStateException.class);
  }

  private void authenticate(String username) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(username, null, List.of()));
  }
}
