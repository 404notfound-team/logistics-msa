package com.notfound.logistics.gateway;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notfound.logistics.common.api.ApiErrorCode;
import com.notfound.logistics.common.api.ApiErrorResponse;
import com.notfound.logistics.common.api.CommonErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class JwtAuthenticationFilter implements GlobalFilter {
  private final JwtDecoder jwtDecoder;
  private final ObjectMapper objectMapper;
  private final WebClient usersClient;

  public JwtAuthenticationFilter(
      JwtDecoder jwtDecoder,
      ObjectMapper objectMapper,
      @LoadBalanced WebClient.Builder webClientBuilder) {
    this.jwtDecoder = jwtDecoder;
    this.objectMapper = objectMapper;
    this.usersClient = webClientBuilder.baseUrl("http://users").build();
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    var request = exchange.getRequest();
    String path = request.getPath().value();
    if (!path.startsWith("/api/") || isPublicUserEndpoint(path)) {
      return chain.filter(exchange);
    }

    String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
    if (authorization == null || !authorization.startsWith("Bearer ")) {
      return unauthorized(exchange.getResponse());
    }
    try {
      var jwt = jwtDecoder.decode(authorization.substring(7));
      String subject = jwt.getSubject();
      String tokenRole = jwt.getClaimAsString("role");
      Object tokenDeliveryStaff = jwt.getClaims().get("is_delivery_staff");
      if (subject == null
          || tokenRole == null
          || !(tokenDeliveryStaff instanceof Boolean)
          || !isKnownRole(tokenRole)) {
        return unauthorized(exchange.getResponse());
      }
      UUID userId = UUID.fromString(subject);
      return inspectCurrentUser(
              authorization,
              userId,
              tokenRole,
              (Boolean) tokenDeliveryStaff,
              isMasterOnlyEndpoint(request.getMethod(), path))
          .flatMap(
              decision ->
                  switch (decision) {
                    case ALLOW -> chain.filter(exchange);
                    case UNAUTHORIZED -> unauthorized(exchange.getResponse());
                    case FORBIDDEN -> forbidden(exchange.getResponse());
                    case SERVICE_UNAVAILABLE -> serviceUnavailable(exchange.getResponse());
                  });
    } catch (JwtException | IllegalArgumentException exception) {
      return unauthorized(exchange.getResponse());
    }
  }

  private Mono<AuthorizationDecision> inspectCurrentUser(
      String authorization,
      UUID userId,
      String tokenRole,
      boolean tokenDeliveryStaff,
      boolean masterOnly) {
    return usersClient
        .get()
        .uri("/internal/users/{id}/authorization", userId)
        .header(HttpHeaders.AUTHORIZATION, authorization)
        .retrieve()
        .bodyToMono(UserAuthorizationEnvelope.class)
        .timeout(Duration.ofSeconds(2))
        .map(
            envelope -> {
              UserAuthorizationInfo currentUser = envelope.data();
              if (currentUser == null
                  || !currentUser.active()
                  || !userId.equals(currentUser.userId())
                  || !tokenRole.equals(currentUser.role())
                  || !Boolean.valueOf(tokenDeliveryStaff).equals(currentUser.isDeliveryStaff())
                  || !isKnownRole(currentUser.role())) {
                return AuthorizationDecision.UNAUTHORIZED;
              }
              if (masterOnly && !"MASTER".equals(currentUser.role())) {
                return AuthorizationDecision.FORBIDDEN;
              }
              return AuthorizationDecision.ALLOW;
            })
        .onErrorResume(
            WebClientResponseException.Unauthorized.class,
            exception -> Mono.just(AuthorizationDecision.UNAUTHORIZED))
        .onErrorResume(
            WebClientResponseException.class,
            exception -> Mono.just(AuthorizationDecision.SERVICE_UNAVAILABLE))
        .onErrorResume(
            WebClientRequestException.class,
            exception -> Mono.just(AuthorizationDecision.SERVICE_UNAVAILABLE))
        .onErrorResume(
            Exception.class, exception -> Mono.just(AuthorizationDecision.SERVICE_UNAVAILABLE))
        .switchIfEmpty(Mono.just(AuthorizationDecision.SERVICE_UNAVAILABLE));
  }

  private boolean isKnownRole(String role) {
    return "MASTER".equals(role) || "USER".equals(role);
  }

  private boolean isPublicUserEndpoint(String path) {
    // Let the users service return 405 for unsupported methods on public endpoints.
    return "/api/users/signup".equals(path) || "/api/users/signin".equals(path);
  }

  private boolean isMasterOnlyEndpoint(HttpMethod method, String path) {
    if (method == null) {
      return false;
    }

    return (HttpMethod.POST.equals(method) && "/api/users".equals(path))
        || (HttpMethod.PATCH.equals(method) && isResourcePath(path, "/api/users/"))
        || (HttpMethod.DELETE.equals(method)
            && isResourcePath(path, "/api/users/")
            && !"/api/users/me".equals(path))
        || (HttpMethod.POST.equals(method) && "/api/stocks".equals(path))
        || (HttpMethod.DELETE.equals(method) && isResourcePath(path, "/api/orders/"));
  }

  private boolean isResourcePath(String path, String prefix) {
    if (!path.startsWith(prefix)) {
      return false;
    }
    String resourceId = path.substring(prefix.length());
    return !resourceId.isBlank() && !resourceId.contains("/");
  }

  private Mono<Void> unauthorized(ServerHttpResponse response) {
    return writeError(response, CommonErrorCode.UNAUTHORIZED);
  }

  private Mono<Void> forbidden(ServerHttpResponse response) {
    return writeError(response, CommonErrorCode.FORBIDDEN);
  }

  private Mono<Void> serviceUnavailable(ServerHttpResponse response) {
    return writeError(response, CommonErrorCode.SERVICE_UNAVAILABLE);
  }

  private Mono<Void> writeError(ServerHttpResponse response, ApiErrorCode errorCode) {
    response.setStatusCode(HttpStatusCode.valueOf(errorCode.httpStatus()));
    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
    byte[] body;
    try {
      body = objectMapper.writeValueAsBytes(ApiErrorResponse.of(errorCode, null));
    } catch (JsonProcessingException exception) {
      String code = errorCode.code();
      String message = errorCode.message();
      body =
          ("{\"code\":\""
                  + code
                  + "\",\"message\":\""
                  + message
                  + "\",\"trace_id\":null,\"field_errors\":[]}")
              .getBytes(StandardCharsets.UTF_8);
    }
    return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
  }

  private record UserAuthorizationEnvelope(UserAuthorizationInfo data) {}

  private record UserAuthorizationInfo(
      @JsonProperty("user_id") UUID userId,
      boolean active,
      String role,
      @JsonProperty("is_delivery_staff") Boolean isDeliveryStaff) {}

  private enum AuthorizationDecision {
    ALLOW,
    UNAUTHORIZED,
    FORBIDDEN,
    SERVICE_UNAVAILABLE
  }
}
