package com.notfound.logistics.users.infrastructure.config;

import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

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
    return () -> Optional.of(signupActor);
  }
}
