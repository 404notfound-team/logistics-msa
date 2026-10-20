package com.notfound.logistics.users.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;

class JwtConfigurationTest {
  private static final String SECRET = "test-only-signing-key-for-users-module-tests-32bytes";
  private static final UUID USER_ID = UUID.randomUUID();

  private final JwtConfiguration configuration = new JwtConfiguration();

  @Test
  void refreshAndAccessTokensCannotStandInForEachOther() {
    var refreshTokens = configuration.refreshTokenCodec(SECRET, Duration.ofDays(14));
    Instant now = Instant.now();
    String refresh = refreshTokens.issue(USER_ID, 0, now);
    JwtClaimsSet accessClaims =
        JwtClaimsSet.builder()
            .issuer("logistics-users")
            .subject("user01")
            .issuedAt(now)
            .expiresAt(now.plusSeconds(300))
            .claim("user_id", USER_ID.toString())
            .claim("token_version", 0L)
            .build();
    String access =
        configuration
            .jwtEncoder(SECRET)
            .encode(
                JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), accessClaims))
            .getTokenValue();

    assertThat(refreshTokens.verify(refresh)).isPresent();
    assertThat(refreshTokens.verify(access)).isEmpty();
    assertThatThrownBy(() -> configuration.jwtDecoder(SECRET).decode(refresh))
        .isInstanceOf(JwtException.class);
  }
}
