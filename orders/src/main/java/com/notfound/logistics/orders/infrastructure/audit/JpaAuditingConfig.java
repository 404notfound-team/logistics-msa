package com.notfound.logistics.orders.infrastructure.audit;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing(auditorAwareRef = "ordersAuditorAware")
public class JpaAuditingConfig {}
