package com.notfound.logistics.hub.support;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

public final class HubSecurityTestTokens {
  public static final String SECRET = "hub-test-only-signing-key-at-least-32-bytes";
  public static final String ORDERS = "hub-test-only-orders-service-token";
  public static final String USERS = "hub-test-only-users-service-token";
  public static final String COMPANY = "hub-test-only-company-service-token";
  public static final String DELIVERY = "hub-test-only-delivery-service-token";

  private HubSecurityTestTokens() {}

  public static JwtClaimsSet.Builder claims(String username, String role) {
    return JwtClaimsSet.builder()
        .issuer("logistics-users")
        .subject(username)
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(300))
        .claim("role", role);
  }

  public static String token(String username, String role) {
    return sign(claims(username, role).build(), SECRET);
  }

  public static String sign(JwtClaimsSet claims, String secret) {
    return sign(claims, secret, JwsHeader.with(MacAlgorithm.HS256).type("JWT").build());
  }

  public static String sign(JwtClaimsSet claims, String secret, JwsHeader header) {
    var encoder =
        new NimbusJwtEncoder(new ImmutableSecret<>(secret.getBytes(StandardCharsets.UTF_8)));
    return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
  }
}
