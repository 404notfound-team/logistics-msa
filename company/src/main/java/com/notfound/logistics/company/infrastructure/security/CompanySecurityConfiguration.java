package com.notfound.logistics.company.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notfound.logistics.common.api.ApiErrorCode;
import com.notfound.logistics.common.api.ApiErrorResponse;
import com.notfound.logistics.common.api.CommonErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

/**
 * 내부 API(/internal/companies·/internal/products)는 서비스 인증(X-Service-Token)으로, 공개 API(/api/**)는
 * users가 발급한 JWT(HS256, issuer logistics-users, sub=username, role=USER|MASTER, user_id=UUID)로
 * 인증한다. 업무 권한(MASTER 또는 업체 관리자)은 CompanyService가 검사한다.
 */
@Configuration(proxyBeanMethods = false)
public class CompanySecurityConfiguration {
  @Bean
  @Order(1)
  SecurityFilterChain companyInternalSecurityFilterChain(
      HttpSecurity http,
      ObjectMapper mapper,
      @Value("${company.security.internal.orders-token:}") String orders,
      @Value("${company.security.internal.users-token:}") String users)
      throws Exception {
    AuthenticationEntryPoint unauthorized = unauthorized(mapper, false);
    var filter =
        new CompanyServiceAuthenticationFilter(
            Map.of("ORDERS", orders, "USERS", users), unauthorized);
    return stateless(http)
        .securityMatcher("/internal/companies", "/internal/companies/**", "/internal/products/**")
        .addFilterBefore(filter, AnonymousAuthenticationFilter.class)
        .exceptionHandling(
            errors ->
                errors
                    .authenticationEntryPoint(unauthorized)
                    .accessDeniedHandler(forbidden(mapper)))
        .authorizeHttpRequests(
            requests ->
                requests
                    .requestMatchers(HttpMethod.GET, "/internal/companies")
                    .hasAuthority("SERVICE_USERS")
                    .requestMatchers(
                        HttpMethod.GET, "/internal/companies/*", "/internal/products/*")
                    .hasAuthority("SERVICE_ORDERS")
                    .anyRequest()
                    .denyAll())
        .build();
  }

  @Bean
  @Order(2)
  SecurityFilterChain companySecurityFilterChain(
      HttpSecurity http, JwtDecoder companyJwtDecoder, ObjectMapper mapper) throws Exception {
    var authorities = new JwtGrantedAuthoritiesConverter();
    authorities.setAuthoritiesClaimName("role");
    authorities.setAuthorityPrefix("ROLE_");
    var authentication = new JwtAuthenticationConverter();
    authentication.setJwtGrantedAuthoritiesConverter(authorities);
    AuthenticationEntryPoint unauthorized = unauthorized(mapper, true);
    AccessDeniedHandler forbidden = forbidden(mapper);
    return stateless(http)
        .securityMatcher("/api/**")
        .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
        .exceptionHandling(
            errors -> errors.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden))
        .oauth2ResourceServer(
            resource ->
                resource
                    .authenticationEntryPoint(unauthorized)
                    .accessDeniedHandler(forbidden)
                    .jwt(
                        jwt ->
                            jwt.decoder(companyJwtDecoder)
                                .jwtAuthenticationConverter(authentication)))
        .build();
  }

  @Bean
  JwtDecoder companyJwtDecoder(@Value("${company.jwt.secret:}") String secret) {
    // 키가 없으면 앱은 뜨되 모든 토큰을 거부한다(orders와 같은 동작).
    if (secret.isBlank()) {
      return token -> {
        throw new BadJwtException("JWT authentication is not configured");
      };
    }
    byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
    if (bytes.length < 32) {
      throw new IllegalStateException("JWT_SECRET requires at least 32 UTF-8 bytes");
    }
    var decoder =
        NimbusJwtDecoder.withSecretKey(new SecretKeySpec(bytes, "HmacSHA256"))
            .macAlgorithm(MacAlgorithm.HS256)
            .build();
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            new JwtTimestampValidator(Duration.ZERO),
            new JwtIssuerValidator("logistics-users"),
            new JwtClaimValidator<Instant>("exp", Objects::nonNull),
            // sub는 감사 작업자(username, varchar(100))로 쓰므로 공백·초과 길이를 거부한다.
            new JwtClaimValidator<String>(
                "sub", value -> value != null && !value.isBlank() && value.length() <= 100),
            new JwtClaimValidator<String>(
                "role", value -> Set.of("USER", "MASTER").contains(value == null ? "" : value)),
            // user_id는 업체 관리자 여부(manager_user_id) 판단에 쓴다.
            new JwtClaimValidator<Object>("user_id", CompanySecurityConfiguration::isUuid)));
    return decoder;
  }

  private static boolean isUuid(Object value) {
    if (!(value instanceof String text)) {
      return false;
    }
    try {
      return UUID.fromString(text).toString().equalsIgnoreCase(text);
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static HttpSecurity stateless(HttpSecurity http) throws Exception {
    return http.csrf(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
  }

  // 내부 API는 Bearer 토큰을 쓰지 않으므로 WWW-Authenticate를 붙이지 않는다.
  private static AuthenticationEntryPoint unauthorized(ObjectMapper mapper, boolean bearer) {
    return (request, response, exception) -> {
      if (bearer) {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
      }
      writeError(response, mapper, CommonErrorCode.UNAUTHORIZED);
    };
  }

  private static AccessDeniedHandler forbidden(ObjectMapper mapper) {
    return (request, response, exception) ->
        writeError(response, mapper, CommonErrorCode.FORBIDDEN);
  }

  private static void writeError(
      HttpServletResponse response, ObjectMapper mapper, ApiErrorCode errorCode)
      throws IOException {
    response.setStatus(errorCode.httpStatus());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    mapper.writeValue(
        response.getOutputStream(), ApiErrorResponse.of(errorCode, MDC.get("traceId")));
  }
}
