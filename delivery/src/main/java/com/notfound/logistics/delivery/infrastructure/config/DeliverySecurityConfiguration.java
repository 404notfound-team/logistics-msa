package com.notfound.logistics.delivery.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notfound.logistics.common.api.ApiErrorResponse;
import com.notfound.logistics.common.api.CommonErrorCode;
import com.notfound.logistics.delivery.infrastructure.security.InternalApiAuthorizationManager;
import com.notfound.logistics.delivery.infrastructure.security.InternalAuthProperties;
import com.notfound.logistics.delivery.infrastructure.security.ServiceTokenAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(InternalAuthProperties.class)
public class DeliverySecurityConfiguration {
  @Bean
  AuthenticationEntryPoint deliveryAuthenticationEntryPoint(ObjectMapper objectMapper) {
    return (request, response, exception) -> {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      objectMapper.writeValue(
          response.getOutputStream(), ApiErrorResponse.of(CommonErrorCode.UNAUTHORIZED, null));
    };
  }

  @Bean
  AccessDeniedHandler deliveryAccessDeniedHandler(ObjectMapper objectMapper) {
    return (request, response, exception) -> {
      response.setStatus(HttpServletResponse.SC_FORBIDDEN);
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      objectMapper.writeValue(
          response.getOutputStream(), ApiErrorResponse.of(CommonErrorCode.FORBIDDEN, null));
    };
  }

  /** 서비스 간 배송 API는 X-Service-Token으로 인증하고, 호출 서비스별 허용 API만 통과시킨다. */
  @Bean
  @Order(1)
  SecurityFilterChain internalApiSecurityFilterChain(
      HttpSecurity http,
      InternalAuthProperties internalAuthProperties,
      AuthenticationEntryPoint deliveryAuthenticationEntryPoint,
      AccessDeniedHandler deliveryAccessDeniedHandler)
      throws Exception {
    return stateless(http)
        .securityMatcher("/internal/deliveries", "/internal/deliveries/**")
        .addFilterBefore(
            new ServiceTokenAuthenticationFilter(internalAuthProperties), AuthorizationFilter.class)
        .authorizeHttpRequests(
            authorize ->
                authorize
                    .anyRequest()
                    .access(new InternalApiAuthorizationManager(internalAuthProperties)))
        .exceptionHandling(
            exceptions ->
                exceptions
                    .authenticationEntryPoint(deliveryAuthenticationEntryPoint)
                    .accessDeniedHandler(deliveryAccessDeniedHandler))
        .build();
  }

  /**
   * 내부 배송 API 외의 요청은 지금처럼 인증 없이 통과시킨다.
   *
   * <p>TODO: 사용자 API(/api/deliveries/**)에 JWT 인증과 권한 검사 적용하기
   */
  @Bean
  @Order(2)
  SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
    return stateless(http)
        .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
        .build();
  }

  private HttpSecurity stateless(HttpSecurity http) throws Exception {
    return http.csrf(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
  }
}
