package com.notfound.logistics.delivery.infrastructure.audit;

import com.notfound.logistics.delivery.infrastructure.security.ServiceAuthentication;
import java.util.Optional;
import org.springframework.data.domain.AuditorAware;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * 감사 작업자는 검증된 JWT의 sub 또는 서비스 인증을 통과한 X-Actor-Username에서 읽는다.
 *
 * <p>공통 계약에 따라 작업자가 없으면 임의 값(system 등)으로 대체하지 않고 예외를 던져 저장을 막는다.
 */
@Component("deliveryAuditorAware")
class DeliveryAuditorAware implements AuditorAware<String> {
  private static final int MAX_USERNAME_LENGTH = 100;

  @Override
  public Optional<String> getCurrentAuditor() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    String actor;
    if (authentication instanceof ServiceAuthentication service) {
      actor = service.getActorUsername();
    } else if (authentication instanceof JwtAuthenticationToken jwt && jwt.isAuthenticated()) {
      actor = jwt.getName();
    } else {
      throw new IllegalStateException("An audit actor requires authenticated service or JWT");
    }
    // created_by·updated_by 컬럼(varchar(100), NOT NULL)에 맞지 않는 값은 거절한다.
    if (actor == null || actor.isBlank() || actor.length() > MAX_USERNAME_LENGTH) {
      throw new IllegalStateException("A valid X-Actor-Username is required");
    }
    return Optional.of(actor);
  }
}
