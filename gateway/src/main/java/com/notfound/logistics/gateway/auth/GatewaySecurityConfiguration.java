package com.notfound.logistics.gateway.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.security.web.server.authentication.ServerAuthenticationEntryPointFailureHandler;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.security.web.server.util.matcher.OrServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher.MatchResult;

/**
 * Gateway 인증·인가 규칙입니다. /api/** 요청의 JWT를 검증하고 Users의 현재 사용자 상태와 대조한 뒤(401·503), 경로별 최소 역할을
 * 검사합니다(403). 소유자·담당자 같은 업무 권한은 각 서비스가 검사합니다.
 */
@Configuration
@EnableWebFluxSecurity
public class GatewaySecurityConfiguration {
  private static final String[] PUBLIC_USER_PATHS = {"/api/users/signup", "/api/users/signin"};

  @Bean
  SecurityWebFilterChain gatewaySecurityFilterChain(
      ServerHttpSecurity http,
      CurrentUserAuthenticationConverter currentUserConverter,
      ApiErrorResponseWriter errors) {
    var entryPoint = errors.authenticationEntryPoint();
    var failureHandler = new ServerAuthenticationEntryPointFailureHandler(entryPoint);
    // Users 조회 실패(AuthenticationServiceException)도 예외를 다시 던지지 않고 entry point가 503으로 응답합니다.
    failureHandler.setRethrowAuthenticationServiceException(false);

    return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
        .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
        .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
        .logout(ServerHttpSecurity.LogoutSpec::disable)
        .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
        .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
        .authorizeExchange(
            exchanges ->
                exchanges
                    // 지원하지 않는 메서드도 Users가 405로 응답하도록 메서드와 관계없이 통과시킵니다.
                    .pathMatchers(PUBLIC_USER_PATHS)
                    .permitAll()
                    .pathMatchers(HttpMethod.GET, "/api/users/me")
                    .authenticated()
                    .pathMatchers(HttpMethod.DELETE, "/api/users/me")
                    .authenticated()
                    .pathMatchers(HttpMethod.GET, "/api/users", "/api/users/*")
                    .hasRole("MASTER")
                    .pathMatchers(HttpMethod.POST, "/api/users", "/api/stocks")
                    .hasRole("MASTER")
                    .pathMatchers(HttpMethod.PATCH, "/api/users/*")
                    .hasRole("MASTER")
                    .pathMatchers(HttpMethod.DELETE, "/api/users/*", "/api/orders/*")
                    .hasRole("MASTER")
                    .pathMatchers("/api/**")
                    .authenticated()
                    // Actuator, Swagger, 로컬 점검 경로는 Gateway 인증 대상이 아닙니다.
                    .anyExchange()
                    .permitAll())
        .oauth2ResourceServer(
            resourceServer ->
                resourceServer
                    .bearerTokenConverter(apiBearerTokenConverter())
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(currentUserConverter))
                    .authenticationEntryPoint(entryPoint)
                    .authenticationFailureHandler(failureHandler)
                    .accessDeniedHandler(errors.accessDeniedHandler()))
        .exceptionHandling(
            exceptions ->
                exceptions
                    .authenticationEntryPoint(entryPoint)
                    .accessDeniedHandler(errors.accessDeniedHandler()))
        .build();
  }

  /**
   * 인증 대상 경로(/api/** 중 공개 경로 제외)에서만 Bearer 토큰을 읽습니다. 공개 경로·문서 경로에 만료된 토큰이 함께 와도 401로 막지 않기 위해서입니다.
   */
  private ServerAuthenticationConverter apiBearerTokenConverter() {
    ServerWebExchangeMatcher api = new PathPatternParserServerWebExchangeMatcher("/api/**");
    ServerWebExchangeMatcher publicPaths =
        new OrServerWebExchangeMatcher(
            new PathPatternParserServerWebExchangeMatcher(PUBLIC_USER_PATHS[0]),
            new PathPatternParserServerWebExchangeMatcher(PUBLIC_USER_PATHS[1]));
    var bearer = new ServerBearerTokenAuthenticationConverter();
    return exchange ->
        api.matches(exchange)
            .filter(MatchResult::isMatch)
            .flatMap(match -> publicPaths.matches(exchange))
            .filter(match -> !match.isMatch())
            .flatMap(match -> bearer.convert(exchange));
  }
}
