package com.notfound.logistics.users.infrastructure.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration
public class JwtConfiguration {
  @Bean
  JwtDecoder jwtDecoder(@Value("${users.jwt.secret}") String secret) {
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withSecretKey(signingKey(secret)).macAlgorithm(MacAlgorithm.HS256).build();
    OAuth2TokenValidator<Jwt> validators =
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefault(), new JwtIssuerValidator("logistics-users"));
    decoder.setJwtValidator(validators);
    return decoder;
  }

  @Bean
  JwtEncoder jwtEncoder(@Value("${users.jwt.secret}") String secret) {
    SecretKey key = signingKey(secret);
    return new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(key));
  }

  static SecretKey signingKey(String secret) {
    byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
    if (bytes.length < 32) {
      throw new IllegalStateException("JWT_SECRET must contain at least 32 UTF-8 bytes");
    }
    return new SecretKeySpec(bytes, "HmacSHA256");
  }
}
