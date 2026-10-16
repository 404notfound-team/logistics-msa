package com.notfound.logistics.hub.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
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
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(HubInternalAuthProperties.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class HubSecurityConfiguration {
  @Bean
  HubSecurityErrorHandler hubSecurityErrorHandler(ObjectMapper mapper) {
    return new HubSecurityErrorHandler(mapper);
  }

  @Bean
  @Order(1)
  SecurityFilterChain hubInternalSecurityFilterChain(
      HttpSecurity http, HubSecurityErrorHandler errors, HubInternalAuthProperties properties)
      throws Exception {
    var filter =
        new HubServiceAuthenticationFilter(
            properties.clients().entrySet().stream()
                .collect(
                    Collectors.toMap(
                        entry -> entry.getKey().toUpperCase(Locale.ROOT),
                        entry -> entry.getValue().token())),
            errors);
    return stateless(http)
        .securityMatcher(
            "/internal/hubs",
            "/internal/hubs/**",
            "/internal/hub-routes",
            "/internal/hub-routes/**",
            "/internal/stocks",
            "/internal/stocks/**")
        .addFilterBefore(filter, AnonymousAuthenticationFilter.class)
        .exceptionHandling(
            handler -> handler.authenticationEntryPoint(errors).accessDeniedHandler(errors))
        .authorizeHttpRequests(
            requests ->
                requests.anyRequest().access(new HubInternalApiAuthorizationManager(properties)))
        .build();
  }

  @Bean
  @Order(2)
  SecurityFilterChain hubExternalSecurityFilterChain(
      HttpSecurity http, JwtDecoder hubJwtDecoder, HubSecurityErrorHandler errors)
      throws Exception {
    var authorities = new JwtGrantedAuthoritiesConverter();
    authorities.setAuthoritiesClaimName("role");
    authorities.setAuthorityPrefix("ROLE_");
    var authentication = new JwtAuthenticationConverter();
    authentication.setJwtGrantedAuthoritiesConverter(authorities);
    return stateless(http)
        .securityMatcher("/api/**")
        .exceptionHandling(
            handler -> handler.authenticationEntryPoint(errors).accessDeniedHandler(errors))
        .authorizeHttpRequests(
            requests ->
                requests
                    .requestMatchers(
                        HttpMethod.POST, "/api/stocks", "/api/hubs/*/products/*/stocks/add")
                    .hasRole("MASTER")
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            resource ->
                resource
                    .authenticationEntryPoint(errors)
                    .jwt(
                        jwt ->
                            jwt.decoder(hubJwtDecoder).jwtAuthenticationConverter(authentication)))
        .build();
  }

  @Bean
  @Order(3)
  SecurityFilterChain hubInfrastructureSecurityFilterChain(
      HttpSecurity http, HubSecurityErrorHandler errors) throws Exception {
    return stateless(http)
        .exceptionHandling(
            handler -> handler.authenticationEntryPoint(errors).accessDeniedHandler(errors))
        .authorizeHttpRequests(
            requests ->
                requests
                    .requestMatchers(
                        HttpMethod.GET,
                        "/actuator/health",
                        "/actuator/health/**",
                        "/v3/api-docs",
                        "/v3/api-docs/**",
                        "/internal/infra/ping")
                    .permitAll()
                    .anyRequest()
                    .denyAll())
        .build();
  }

  @Bean
  JwtDecoder hubJwtDecoder(@Value("${hub.jwt.secret:}") String secret) {
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
            new JwtClaimValidator<Object>(
                "sub",
                value ->
                    value instanceof String username
                        && !username.isBlank()
                        && username.length() <= 100),
            new JwtClaimValidator<Object>(
                "role",
                value -> value instanceof String role && Set.of("USER", "MASTER").contains(role))));
    return decoder;
  }

  private HttpSecurity stateless(HttpSecurity http) throws Exception {
    return http.csrf(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
  }
}
