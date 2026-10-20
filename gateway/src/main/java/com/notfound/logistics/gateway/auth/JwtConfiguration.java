package com.notfound.logistics.gateway.auth;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

@Configuration
public class JwtConfiguration {
  static final Set<String> ROLES = Set.of("MASTER", "USER");

  /**
   * 서명·만료·발급자와 Gateway가 쓰는 클레임(sub, user_id, role, is_delivery_staff, token_version)의 형식을 검증합니다.
   */
  @Bean
  ReactiveJwtDecoder jwtDecoder(
      @Value("${spring.security.oauth2.resourceserver.jwt.secret}") String secret) {
    NimbusReactiveJwtDecoder decoder =
        NimbusReactiveJwtDecoder.withSecretKey(signingKey(secret))
            .macAlgorithm(MacAlgorithm.HS256)
            .build();
    OAuth2TokenValidator<Jwt> validators =
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefault(),
            new JwtIssuerValidator("logistics-users"),
            JwtConfiguration::validateClaims);
    decoder.setJwtValidator(validators);
    return decoder;
  }

  private static OAuth2TokenValidatorResult validateClaims(Jwt jwt) {
    String subject = jwt.getSubject();
    boolean valid =
        subject != null
            && !subject.isBlank()
            && isUuid(jwt.getClaimAsString("user_id"))
            && ROLES.contains(jwt.getClaimAsString("role"))
            && jwt.getClaims().get("is_delivery_staff") instanceof Boolean
            && jwt.getClaims().get("token_version") instanceof Long;
    return valid
        ? OAuth2TokenValidatorResult.success()
        : OAuth2TokenValidatorResult.failure(
            new OAuth2Error("invalid_token", "Required claims are missing or malformed", null));
  }

  private static boolean isUuid(String value) {
    if (value == null) {
      return false;
    }
    try {
      UUID.fromString(value);
      return true;
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private SecretKey signingKey(String secret) {
    byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
    if (bytes.length < 32) {
      throw new IllegalStateException("JWT_SECRET must contain at least 32 UTF-8 bytes");
    }
    return new SecretKeySpec(bytes, "HmacSHA256");
  }
}
