package com.notfound.logistics.orders.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notfound.logistics.common.api.ApiErrorResponse;
import com.notfound.logistics.common.api.CommonErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
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

@Configuration(proxyBeanMethods = false)
public class OrderSecurityConfiguration {
  @Bean
  SecurityFilterChain orderSecurityFilterChain(
      HttpSecurity http, JwtDecoder orderJwtDecoder, ObjectMapper mapper) throws Exception {
    var authorities = new JwtGrantedAuthoritiesConverter();
    authorities.setAuthoritiesClaimName("role");
    authorities.setAuthorityPrefix("ROLE_");
    var authentication = new JwtAuthenticationConverter();
    authentication.setJwtGrantedAuthoritiesConverter(authorities);
    AuthenticationEntryPoint unauthorized =
        (request, response, exception) -> {
          response.setStatus(401);
          response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
          response.setContentType(MediaType.APPLICATION_JSON_VALUE);
          mapper.writeValue(
              response.getOutputStream(),
              ApiErrorResponse.of(CommonErrorCode.UNAUTHORIZED, MDC.get("traceId")));
        };
    return http.securityMatcher("/api/orders", "/api/orders/**")
        .csrf(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
        .exceptionHandling(errors -> errors.authenticationEntryPoint(unauthorized))
        .oauth2ResourceServer(
            resource ->
                resource
                    .authenticationEntryPoint(unauthorized)
                    .jwt(
                        jwt ->
                            jwt.decoder(orderJwtDecoder)
                                .jwtAuthenticationConverter(authentication)))
        .build();
  }

  @Bean
  JwtDecoder orderJwtDecoder(@Value("${orders.jwt.secret:}") String secret) {
    if (secret.isBlank()) {
      return token -> {
        throw new BadJwtException("JWT authentication is not configured");
      };
    }
    byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
    if (bytes.length < 32)
      throw new IllegalStateException("JWT_SECRET requires at least 32 UTF-8 bytes");
    var decoder =
        NimbusJwtDecoder.withSecretKey(new SecretKeySpec(bytes, "HmacSHA256"))
            .macAlgorithm(MacAlgorithm.HS256)
            .build();
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            new JwtTimestampValidator(Duration.ZERO),
            new JwtIssuerValidator("logistics-users"),
            new JwtClaimValidator<Instant>("exp", Objects::nonNull),
            new JwtClaimValidator<String>(
                "sub", value -> value != null && !value.isBlank() && value.length() <= 100),
            new JwtClaimValidator<String>(
                "role", value -> Set.of("USER", "MASTER").contains(value == null ? "" : value))));
    return decoder;
  }
}
