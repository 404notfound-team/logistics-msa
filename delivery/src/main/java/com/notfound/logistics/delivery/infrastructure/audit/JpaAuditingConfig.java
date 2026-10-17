package com.notfound.logistics.delivery.infrastructure.audit;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * AuditEntity의 생성·수정 시각과 작업자(created_by, updated_by)를 저장 시 자동으로 채운다. 작업자는 deliveryAuditorAware가
 * 제공한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing(auditorAwareRef = "deliveryAuditorAware")
public class JpaAuditingConfig {}
