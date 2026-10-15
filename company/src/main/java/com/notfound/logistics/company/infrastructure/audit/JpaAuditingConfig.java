package com.notfound.logistics.company.infrastructure.audit;

import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing
class JpaAuditingConfig {
  // TODO: Gateway 인증 작업 후 로그인한 사용자 username을 반환하도록 교체한다.
  //  공통 계약상 임의 작업자 사용은 금지이므로 이 값은 기능 확인용 임시값이며 PR 전에 반드시 제거한다.
  @Bean
  AuditorAware<String> auditorAware() {
    return () -> Optional.of("temp-user");
  }
}
