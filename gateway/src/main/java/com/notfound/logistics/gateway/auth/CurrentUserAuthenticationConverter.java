package com.notfound.logistics.gateway.auth;

import com.notfound.logistics.gateway.auth.UsersAuthorizationClient.CurrentUser;
import java.util.List;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 검증된 JWT를 Users의 현재 사용자 상태와 대조해 인증 객체로 바꿉니다. 탈퇴했거나 JWT 발급 이후 username·역할·배송 담당 여부가 바뀐 사용자의 토큰은
 * 거절합니다. 인증 주체 이름은 sub(username), 권한은 {@code ROLE_<role>}입니다.
 */
@Component
public class CurrentUserAuthenticationConverter
    implements Converter<Jwt, Mono<AbstractAuthenticationToken>> {
  private final UsersAuthorizationClient usersClient;

  public CurrentUserAuthenticationConverter(UsersAuthorizationClient usersClient) {
    this.usersClient = usersClient;
  }

  @Override
  public Mono<AbstractAuthenticationToken> convert(Jwt jwt) {
    UUID userId = UUID.fromString(jwt.getClaimAsString("user_id"));
    return usersClient
        .fetch(userId)
        .flatMap(
            currentUser -> {
              if (!matches(jwt, userId, currentUser)) {
                return Mono.error(
                    new InvalidBearerTokenException("Token no longer matches the current user"));
              }
              var authority = new SimpleGrantedAuthority("ROLE_" + currentUser.role());
              return Mono.just(
                  new JwtAuthenticationToken(jwt, List.of(authority), jwt.getSubject()));
            });
  }

  private boolean matches(Jwt jwt, UUID userId, CurrentUser currentUser) {
    return currentUser.active()
        && userId.equals(currentUser.userId())
        && jwt.getSubject().equals(currentUser.username())
        && jwt.getClaimAsString("role").equals(currentUser.role())
        && JwtConfiguration.ROLES.contains(currentUser.role())
        && jwt.getClaims().get("is_delivery_staff").equals(currentUser.isDeliveryStaff());
  }
}
