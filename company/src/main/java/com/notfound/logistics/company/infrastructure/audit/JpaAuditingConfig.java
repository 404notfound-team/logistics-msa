package com.notfound.logistics.company.infrastructure.audit;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing(auditorAwareRef = "companyAuditorAware")
class JpaAuditingConfig {}
