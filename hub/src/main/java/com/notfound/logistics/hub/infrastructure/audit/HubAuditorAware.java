package com.notfound.logistics.hub.infrastructure.audit;

import java.util.Optional;
import org.springframework.data.domain.AuditorAware;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component("hubAuditorAware")
public class HubAuditorAware implements AuditorAware<String> {

  @Override
  public Optional<String> getCurrentAuditor() {
    // 외부 JWT 또는 내부 서비스 인증을 통과한 원래 요청자의 username만 사용한다.
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null
        || !authentication.isAuthenticated()
        || authentication instanceof AnonymousAuthenticationToken) {
      throw new IllegalStateException("검증된 Hub 감사 주체가 없습니다.");
    }
    String username = authentication.getName();
    if (username == null || username.isBlank() || username.length() > 100) {
      throw new IllegalStateException("유효한 감사 username이 필요합니다.");
    }
    return Optional.of(username);
  }
}
