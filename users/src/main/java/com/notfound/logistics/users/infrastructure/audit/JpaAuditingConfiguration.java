package com.notfound.logistics.users.infrastructure.audit;

import com.notfound.logistics.users.infrastructure.security.ServiceAuthentication;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

@Configuration
@ConditionalOnProperty(
    prefix = "users.audit",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@EnableJpaAuditing(auditorAwareRef = "usersAuditorAware")
public class JpaAuditingConfiguration {
  @Bean
  AuditorAware<String> usersAuditorAware(
      @Value("${users.audit.signup-actor:signup-service}") String signupActor) {
    if (signupActor.isBlank()) {
      throw new IllegalStateException("users.audit.signup-actor must not be blank");
    }
    return () -> Optional.of(currentActor(signupActor));
  }

  /** 회원가입 범위면 지정된 시스템 작업자, 그 밖에는 인증된 사용자(JWT sub) 또는 내부 호출의 원 요청자입니다. */
  static String currentActor(String signupActor) {
    if (SignupAuditScope.isActive()) {
      return signupActor;
    }
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    String actor = null;
    if (authentication instanceof ServiceAuthentication service) {
      actor = service.getActorUsername();
    } else if (authentication != null
        && authentication.isAuthenticated()
        && !(authentication instanceof AnonymousAuthenticationToken)) {
      actor = authentication.getName();
    }
    if (actor == null || actor.isBlank()) {
      throw new IllegalStateException("No audit actor for this write");
    }
    return actor;
  }
}
