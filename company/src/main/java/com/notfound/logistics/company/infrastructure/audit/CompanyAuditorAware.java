package com.notfound.logistics.company.infrastructure.audit;

import java.util.Optional;
import org.springframework.data.domain.AuditorAware;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 인증된 사용자의 username(JWT sub)을 감사 작업자로 쓴다. 인증 주체가 없으면 임의 작업자로 채우지 않고 실패한다(공통 계약). 생성·수정 감사와
 * CompanyService의 삭제 기록이 같은 출처를 쓴다.
 */
@Component("companyAuditorAware")
public class CompanyAuditorAware implements AuditorAware<String> {
  @Override
  public Optional<String> getCurrentAuditor() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null
        || !authentication.isAuthenticated()
        || authentication instanceof AnonymousAuthenticationToken) {
      throw new IllegalStateException("A verified audit actor is required");
    }
    var username = authentication.getName();
    if (username == null || username.isBlank() || username.length() > 100) {
      throw new IllegalStateException("A valid audit username is required");
    }
    return Optional.of(username);
  }
}
