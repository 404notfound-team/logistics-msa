package com.notfound.logistics.users.infrastructure.client;

import com.notfound.logistics.users.infrastructure.security.ServiceAuthentication;
import com.notfound.logistics.users.infrastructure.security.ServiceTokenAuthenticationFilter;
import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * 다른 서비스의 내부 API를 호출할 때 Users 서비스 인증값과 원 요청자를 붙입니다. 전역 설정이 되지 않도록 {@code @Configuration} 없이 각
 * {@code @FeignClient(configuration = ...)}에서 지정합니다.
 */
public class InternalServiceFeignConfiguration {
  @Bean
  RequestInterceptor internalServiceAuthInterceptor(
      @Value("${users.internal-auth.service-token}") String serviceToken) {
    if (serviceToken.isBlank()) {
      throw new IllegalStateException("USERS_SERVICE_TOKEN must not be blank");
    }
    return template -> {
      template.header(ServiceTokenAuthenticationFilter.SERVICE_TOKEN_HEADER, serviceToken);
      String actor = actorUsername(SecurityContextHolder.getContext().getAuthentication());
      // Signup lookups run before login, so they legitimately have no actor.
      if (actor != null) {
        template.header(ServiceTokenAuthenticationFilter.ACTOR_USERNAME_HEADER, actor);
      }
    };
  }

  static String actorUsername(Authentication authentication) {
    if (authentication instanceof JwtAuthenticationToken jwt) {
      return jwt.getName();
    }
    if (authentication instanceof ServiceAuthentication service) {
      return service.getActorUsername();
    }
    return null;
  }
}
