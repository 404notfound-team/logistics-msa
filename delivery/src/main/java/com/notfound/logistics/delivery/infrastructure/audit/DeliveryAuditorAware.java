package com.notfound.logistics.delivery.infrastructure.audit;

import java.util.Optional;
import org.springframework.data.domain.AuditorAware;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 감사 필드의 작업자(username)를 제공한다. 내부 API 호출 서비스가 보낸 X-Actor-Username을 원 요청자로 사용한다.
 *
 * <p>공통 계약에 따라 작업자가 없으면 임의 값(system 등)으로 대체하지 않고 예외를 던져 저장을 막는다.
 *
 * <p>TODO: 수신 측 서비스 인증을 구현하면 인증을 통과한 SecurityContext의 주체로 바꾸기
 */
@Component("deliveryAuditorAware")
class DeliveryAuditorAware implements AuditorAware<String> {
  static final String ACTOR_USERNAME_HEADER = "X-Actor-Username";
  private static final int MAX_USERNAME_LENGTH = 100;

  @Override
  public Optional<String> getCurrentAuditor() {
    // HTTP 요청 스레드가 아니면(배치·비동기 등) 원 요청자를 알 수 없다.
    if (!(RequestContextHolder.getRequestAttributes()
        instanceof ServletRequestAttributes attributes)) {
      throw new IllegalStateException("An audit actor requires an HTTP request");
    }
    String actor = attributes.getRequest().getHeader(ACTOR_USERNAME_HEADER);
    // created_by·updated_by 컬럼(varchar(100), NOT NULL)에 맞지 않는 값은 거절한다.
    if (actor == null || actor.isBlank() || actor.length() > MAX_USERNAME_LENGTH) {
      throw new IllegalStateException("A valid X-Actor-Username is required");
    }
    return Optional.of(actor);
  }
}
