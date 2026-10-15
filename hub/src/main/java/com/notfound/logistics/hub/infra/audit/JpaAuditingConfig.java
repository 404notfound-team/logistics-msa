package com.notfound.logistics.hub.infra.audit;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@Configuration
@EnableJpaAuditing(auditorAwareRef = "hubAuditorAware")
public class JpaAuditingConfig {}
