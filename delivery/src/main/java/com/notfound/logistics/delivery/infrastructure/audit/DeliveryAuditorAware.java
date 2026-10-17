package com.notfound.logistics.delivery.infrastructure.audit;

import com.notfound.logistics.delivery.infrastructure.security.ServiceAuthentication;
import java.util.Optional;
import org.springframework.data.domain.AuditorAware;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 감사 필드의 작업자(username)를 제공한다. 서비스 인증을 통과한 내부 API 호출의 X-Actor-Username을 원 요청자로 사용한다.
 *
 * <p>공통 계약에 따라 작업자가 없으면 임의 값(system 등)으로 대체하지 않고 예외를 던져 저장을 막는다.
 */
@Component("deliveryAuditorAware")
class DeliveryAuditorAware implements AuditorAware<String> {
  private static final int MAX_USERNAME_LENGTH = 100;

  @Override
  public Optional<String> getCurrentAuditor() {
    // 인증된 서비스 호출이 아니면(배치·비동기 등) 원 요청자를 알 수 없다.
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (!(authentication instanceof ServiceAuthentication service)) {
      throw new IllegalStateException("An audit actor requires an authenticated service call");
    }
    String actor = service.getActorUsername();
    // created_by·updated_by 컬럼(varchar(100), NOT NULL)에 맞지 않는 값은 거절한다.
    if (actor == null || actor.length() > MAX_USERNAME_LENGTH) {
      throw new IllegalStateException("A valid X-Actor-Username is required");
    }
    return Optional.of(actor);
  }
}
