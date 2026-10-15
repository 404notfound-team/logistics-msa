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
    // TODO(Users/Gateway): 인증 계층에서 검증한 username을 SecurityContext에 전달하도록 연동한다.
    // 미연동 상태에서도 요청 헤더를 직접 신뢰하거나 임의 감사 주체로 대체하지 않는다.
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null
        || !authentication.isAuthenticated()
        || authentication instanceof AnonymousAuthenticationToken) {
      throw new IllegalStateException("Hub 감사 주체 연동이 필요합니다: 검증된 인증 주체가 없습니다.");
    }
    String username = authentication.getName();
    if (username == null || username.isBlank() || username.length() > 100) {
      throw new IllegalStateException("유효한 감사 username이 필요합니다.");
    }
    return Optional.of(username);
  }
}
