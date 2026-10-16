package com.notfound.logistics.company.infrastructure.client;

import feign.Request;
import feign.RequestInterceptor;
import feign.Retryer;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.AuditorAware;

// HubClient 전용 설정이라 @Configuration을 붙이지 않는다. 붙이면 모든 Feign 클라이언트에 적용된다.
class HubFeignConfiguration {
  @Bean
  Retryer hubRetryer() {
    return Retryer.NEVER_RETRY;
  }

  // Gateway의 사용자 상태 확인과 같은 2초 안에 응답이 없으면 hub 장애로 본다.
  @Bean
  Request.Options hubRequestOptions(
      @Value("${company.clients.connect-timeout-ms:1000}") int connect,
      @Value("${company.clients.read-timeout-ms:2000}") int read) {
    return new Request.Options(connect, TimeUnit.MILLISECONDS, read, TimeUnit.MILLISECONDS, false);
  }

  // 공통 계약: 서비스 인증값과 원 요청자의 username을 함께 보낸다.
  @Bean
  RequestInterceptor hubRequestHeaders(
      @Qualifier("companyAuditorAware") AuditorAware<String> auditor,
      @Value("${company.clients.service-token:}") String serviceToken) {
    return template -> {
      if (!serviceToken.isBlank()) {
        template.header("X-Service-Token", serviceToken);
      }
      template.header("X-Actor-Username", auditor.getCurrentAuditor().orElseThrow());
    };
  }
}
