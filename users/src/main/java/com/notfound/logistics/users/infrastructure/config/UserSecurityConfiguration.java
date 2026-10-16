package com.notfound.logistics.users.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notfound.logistics.common.api.ApiErrorResponse;
import com.notfound.logistics.common.api.CommonErrorCode;
import com.notfound.logistics.users.infrastructure.security.InternalApiAuthorizationManager;
import com.notfound.logistics.users.infrastructure.security.InternalAuthProperties;
import com.notfound.logistics.users.infrastructure.security.ServiceTokenAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(InternalAuthProperties.class)
public class UserSecurityConfiguration {
  @Bean
  AuthenticationEntryPoint userAuthenticationEntryPoint(ObjectMapper objectMapper) {
    return (request, response, exception) -> {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      objectMapper.writeValue(
          response.getOutputStream(), ApiErrorResponse.of(CommonErrorCode.UNAUTHORIZED, null));
    };
  }

  @Bean
  AccessDeniedHandler userAccessDeniedHandler(ObjectMapper objectMapper) {
    return (request, response, exception) -> {
      response.setStatus(HttpServletResponse.SC_FORBIDDEN);
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      objectMapper.writeValue(
          response.getOutputStream(), ApiErrorResponse.of(CommonErrorCode.FORBIDDEN, null));
    };
  }

  /** 서비스 간 내부 API는 사용자 JWT가 아니라 X-Service-Token으로 인증합니다. */
  @Bean
  @Order(1)
  SecurityFilterChain internalApiSecurityFilterChain(
      HttpSecurity http,
      InternalAuthProperties internalAuthProperties,
      AuthenticationEntryPoint userAuthenticationEntryPoint,
      AccessDeniedHandler userAccessDeniedHandler)
      throws Exception {
    return http.securityMatcher("/internal/**")
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .addFilterBefore(
            new ServiceTokenAuthenticationFilter(internalAuthProperties), AuthorizationFilter.class)
        .authorizeHttpRequests(
            authorize ->
                authorize
                    .requestMatchers(HttpMethod.GET, "/internal/infra/ping")
                    .permitAll()
                    .anyRequest()
                    .access(new InternalApiAuthorizationManager(internalAuthProperties)))
        .exceptionHandling(
            exceptions ->
                exceptions
                    .authenticationEntryPoint(userAuthenticationEntryPoint)
                    .accessDeniedHandler(userAccessDeniedHandler))
        .build();
  }

  @Bean
  @Order(2)
  SecurityFilterChain userSecurityFilterChain(
      HttpSecurity http,
      AuthenticationEntryPoint userAuthenticationEntryPoint,
      AccessDeniedHandler userAccessDeniedHandler)
      throws Exception {
    return http.csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            authorize ->
                authorize
                    .requestMatchers("/api/users/signup", "/api/users/signin")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/users/me")
                    .authenticated()
                    .requestMatchers(HttpMethod.DELETE, "/api/users/me")
                    .authenticated()
                    // 사용자 목록·관리는 MASTER 전용입니다. Gateway도 같은 경로를 막지만 우회 호출에 대비해 다시 검사합니다.
                    .requestMatchers(HttpMethod.GET, "/api/users", "/api/users/*")
                    .hasRole("MASTER")
                    .requestMatchers(HttpMethod.POST, "/api/users")
                    .hasRole("MASTER")
                    .requestMatchers(HttpMethod.PATCH, "/api/users/*")
                    .hasRole("MASTER")
                    .requestMatchers(HttpMethod.DELETE, "/api/users/*")
                    .hasRole("MASTER")
                    .requestMatchers(
                        "/actuator/**",
                        "/v3/api-docs",
                        "/v3/api-docs/**",
                        "/v3/api-docs.yaml",
                        "/swagger-ui.html",
                        "/swagger-ui/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            resourceServer ->
                resourceServer
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                    .authenticationEntryPoint(userAuthenticationEntryPoint)
                    .accessDeniedHandler(userAccessDeniedHandler))
        .build();
  }

  /** 인증 주체 이름은 JWT의 sub(username), 권한은 role 클레임의 `ROLE_<role>`입니다. */
  private JwtAuthenticationConverter jwtAuthenticationConverter() {
    JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
    authorities.setAuthoritiesClaimName("role");
    authorities.setAuthorityPrefix("ROLE_");
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(authorities);
    return converter;
  }
}
