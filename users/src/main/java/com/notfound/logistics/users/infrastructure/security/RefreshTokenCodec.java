package com.notfound.logistics.users.infrastructure.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * refresh token을 access token과 다른 키로 서명·검증합니다. 키가 다르므로 refresh token은 access token으로 쓸 수 없고, 그 반대도
 * 마찬가지입니다. 서버에 저장하지 않으며, 로그아웃으로 사용자의 token_version이 오르면 이전 refresh token은 갱신에 쓸 수 없습니다.
 */
public class RefreshTokenCodec {
  private static final String ISSUER = "logistics-users";

  private final NimbusJwtEncoder encoder;
  private final NimbusJwtDecoder decoder;
  private final Duration ttl;

  public RefreshTokenCodec(SecretKey key, Duration ttl) {
    this.encoder = new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(key));
    this.decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    this.decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefault(), new JwtIssuerValidator(ISSUER)));
    this.ttl = ttl;
  }

  public Duration ttl() {
    return ttl;
  }

  public String issue(UUID userId, long tokenVersion, Instant issuedAt) {
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(ISSUER)
            .issuedAt(issuedAt)
            .expiresAt(issuedAt.plus(ttl))
            .claim("user_id", userId.toString())
            .claim("token_version", tokenVersion)
            .build();
    return encoder
        .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
        .getTokenValue();
  }

  /** 서명·만료·발급자·클레임 형식 중 하나라도 맞지 않으면 비어 있습니다. */
  public Optional<RefreshClaims> verify(String token) {
    if (token == null || token.isBlank()) {
      return Optional.empty();
    }
    try {
      Jwt jwt = decoder.decode(token);
      UUID userId = UUID.fromString(jwt.getClaimAsString("user_id"));
      return jwt.getClaims().get("token_version") instanceof Long tokenVersion
          ? Optional.of(new RefreshClaims(userId, tokenVersion))
          : Optional.empty();
    } catch (JwtException | IllegalArgumentException | NullPointerException exception) {
      return Optional.empty();
    }
  }

  public record RefreshClaims(UUID userId, long tokenVersion) {}
}
