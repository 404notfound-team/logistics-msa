package com.notfound.logistics.hub.infra.audit;

import java.util.Optional;
import org.springframework.data.domain.AuditorAware;
import org.springframework.stereotype.Component;

@Component("hubAuditorAware")
public class HubAuditorAware implements AuditorAware<String> {

  @Override
  public Optional<String> getCurrentAuditor() {
    // TODO:
    // Gateway/Users와 인증 주체 전달 방식 합의 후
    // 검증된 인증 정보에서 username을 추출하도록 구현한다.

    throw new IllegalStateException("Hub 감사 주체 연동이 필요합니다: " + "검증된 인증 컨텍스트에서 username을 얻어야 합니다.");
  }
}
