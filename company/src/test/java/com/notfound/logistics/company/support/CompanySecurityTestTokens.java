package com.notfound.logistics.company.support;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** 테스트용 키로 users와 같은 규격의 JWT를 실제로 서명한다. */
public final class CompanySecurityTestTokens {
  public static final String SECRET = "company-test-only-jwt-signing-secret-32-bytes";
  public static final String USERNAME = "test-user";
  // 내부 API 호출 서비스(orders)의 테스트용 X-Service-Token.
  public static final String ORDERS_TOKEN = "orders-test-only-service-token";
  public static final UUID USER_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");

  private CompanySecurityTestTokens() {}

  public static JwtClaimsSet.Builder claims() {
    return JwtClaimsSet.builder()
        .issuer("logistics-users")
        .subject(USERNAME)
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(300))
        .claim("user_id", USER_ID.toString())
        .claim("is_delivery_staff", false)
        .claim("role", "USER");
  }

  public static String bearer(JwtClaimsSet claims) {
    var encoder =
        new NimbusJwtEncoder(new ImmutableSecret<>(SECRET.getBytes(StandardCharsets.UTF_8)));
    return "Bearer "
        + encoder
            .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
            .getTokenValue();
  }

  public static String userBearer() {
    return bearer(claims().build());
  }
}
