package com.notfound.logistics.users.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notfound.logistics.common.api.ApiErrorResponse;
import com.notfound.logistics.common.api.CommonErrorCode;
import com.notfound.logistics.users.infrastructure.security.InternalApiAuthorizationManager;
import com.notfound.logistics.users.infrastructure.security.InternalAuthProperties;
import com.notfound.logistics.users.infrastructure.security.ServiceTokenAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(InternalAuthProperties.class)
public class UserSecurityConfiguration {
  private static final Set<String> PUBLIC_USER_PATHS =
      Set.of("/api/users/signup", "/api/users/signin");

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
      @Value("${users.internal-auth.orders-token:}") String ordersToken,
      @Value("${users.internal-auth.delivery-token:}") String deliveryToken,
      AuthenticationEntryPoint userAuthenticationEntryPoint,
      AccessDeniedHandler userAccessDeniedHandler)
      throws Exception {
    // Orders 토큰이 설정된 환경에서만 알림용 최소 표시 정보 조회를 허용합니다.
    var clients = new HashMap<>(internalAuthProperties.clients());
    if (!ordersToken.isBlank()) {
      clients.putIfAbsent(
          "orders",
          new InternalAuthProperties.Client(
              ordersToken, List.of("GET /internal/users/identities")));
    }
    if (!deliveryToken.isBlank()) {
      clients.putIfAbsent(
          "delivery",
          new InternalAuthProperties.Client(
              deliveryToken, List.of("GET /internal/users/*/authorization")));
    }
    var effectiveProperties = new InternalAuthProperties(clients);
    return http.securityMatcher("/internal/**")
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .addFilterBefore(
            new ServiceTokenAuthenticationFilter(effectiveProperties), AuthorizationFilter.class)
        .authorizeHttpRequests(
            authorize ->
                authorize
                    .requestMatchers(HttpMethod.GET, "/internal/infra/ping")
                    .permitAll()
                    .anyRequest()
                    .access(new InternalApiAuthorizationManager(effectiveProperties)))
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
                    .requestMatchers(PUBLIC_USER_PATHS.toArray(String[]::new))
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
                    .bearerTokenResolver(publicPathIgnoringBearerTokenResolver())
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                    .authenticationEntryPoint(userAuthenticationEntryPoint)
                    .accessDeniedHandler(userAccessDeniedHandler))
        .build();
  }

  /**
   * 로그인·가입은 Authorization 헤더를 읽지 않습니다. permitAll 경로라도 Bearer 필터는 헤더가 있으면 먼저 검증하므로, 클라이언트가 만료된 토큰을
   * 자동 첨부하면 올바른 로그인 정보로도 401이 됩니다.
   */
  private static BearerTokenResolver publicPathIgnoringBearerTokenResolver() {
    DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();
    return request -> {
      String path = request.getRequestURI().substring(request.getContextPath().length());
      return PUBLIC_USER_PATHS.contains(path) ? null : delegate.resolve(request);
    };
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
