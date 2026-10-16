package com.notfound.logistics.gateway.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** Users 내부 API로 현재 사용자 상태를 조회합니다. 사용자 JWT 대신 Gateway 서비스 인증값을 보냅니다. */
@Component
public class UsersAuthorizationClient {
  static final String SERVICE_TOKEN_HEADER = "X-Service-Token";
  private static final Duration TIMEOUT = Duration.ofSeconds(2);

  private final WebClient usersClient;

  public UsersAuthorizationClient(
      @LoadBalanced WebClient.Builder webClientBuilder,
      @Value("${gateway.service-token}") String serviceToken) {
    if (serviceToken.isBlank()) {
      throw new IllegalStateException("GATEWAY_SERVICE_TOKEN must not be blank");
    }
    this.usersClient =
        webClientBuilder
            .baseUrl("http://users")
            .defaultHeader(SERVICE_TOKEN_HEADER, serviceToken)
            .build();
  }

  /** 조회 실패·지연·서비스 인증 거절은 모두 {@link UsersUnavailableException}(503)입니다. */
  public Mono<CurrentUser> fetch(UUID userId) {
    return usersClient
        .get()
        .uri("/internal/users/{id}/authorization", userId)
        .retrieve()
        .bodyToMono(Envelope.class)
        .timeout(TIMEOUT)
        .flatMap(envelope -> Mono.justOrEmpty(envelope.data()))
        .switchIfEmpty(Mono.error(() -> new UsersUnavailableException(null)))
        .onErrorMap(
            exception -> !(exception instanceof UsersUnavailableException),
            UsersUnavailableException::new);
  }

  public record CurrentUser(
      @JsonProperty("user_id") UUID userId,
      String username,
      boolean active,
      String role,
      @JsonProperty("is_delivery_staff") Boolean isDeliveryStaff) {}

  private record Envelope(CurrentUser data) {}

  /** 인증 실패(401)가 아니라 인증을 판단할 수 없는 상태입니다. */
  public static class UsersUnavailableException extends AuthenticationServiceException {
    UsersUnavailableException(Throwable cause) {
      super("Users authorization lookup failed", cause);
    }
  }
}
