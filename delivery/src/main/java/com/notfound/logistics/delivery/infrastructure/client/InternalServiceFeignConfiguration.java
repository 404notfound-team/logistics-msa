package com.notfound.logistics.delivery.infrastructure.client;

import com.notfound.logistics.delivery.infrastructure.security.ServiceAuthentication;
import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 다른 서비스의 내부 API를 호출할 때 Delivery 서비스 인증값과 원 요청자를 헤더에 붙인다. 모든 Feign 클라이언트에 적용되지 않도록
 * {@code @Configuration} 없이 각 {@code @FeignClient(configuration = ...)}에서 지정한다.
 */
public class InternalServiceFeignConfiguration {
  static final String SERVICE_TOKEN_HEADER = "X-Service-Token";
  static final String ACTOR_USERNAME_HEADER = "X-Actor-Username";

  /** 요청마다 X-Service-Token(DELIVERY_SERVICE_TOKEN)과 X-Actor-Username을 추가하는 인터셉터. */
  @Bean
  RequestInterceptor internalServiceAuthInterceptor(
      @Value("${delivery.internal-auth.service-token:}") String serviceToken) {
    // 공통 계약: 발신 서비스는 인증값이 비어 있으면 기동하지 않는다.
    if (serviceToken.isBlank()) {
      throw new IllegalStateException("DELIVERY_SERVICE_TOKEN must not be blank");
    }
    return template -> {
      template.header(SERVICE_TOKEN_HEADER, serviceToken);
      // Hub는 원 요청자 헤더를 필수로 요구한다. 인증된 요청에 있으면 그대로 전달한다.
      String actor = currentActor();
      if (actor != null) {
        template.header(ACTOR_USERNAME_HEADER, actor);
      }
    };
  }

  // 서비스 인증을 통과한 현재 요청의 원 요청자를 읽는다. 인증된 서비스 호출이 아니거나 원 요청자가 없으면 null.
  private static String currentActor() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    return authentication instanceof ServiceAuthentication service
        ? service.getActorUsername()
        : null;
  }
}
