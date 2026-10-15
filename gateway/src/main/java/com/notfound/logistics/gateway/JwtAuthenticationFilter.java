package com.notfound.logistics.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notfound.logistics.common.api.ApiErrorCode;
import com.notfound.logistics.common.api.ApiErrorResponse;
import com.notfound.logistics.common.api.CommonErrorCode;
import java.nio.charset.StandardCharsets;
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
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class JwtAuthenticationFilter implements GlobalFilter {
  private final JwtDecoder jwtDecoder;
  private final ObjectMapper objectMapper;

  public JwtAuthenticationFilter(JwtDecoder jwtDecoder, ObjectMapper objectMapper) {
    this.jwtDecoder = jwtDecoder;
    this.objectMapper = objectMapper;
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
      if (isMasterOnlyEndpoint(request.getMethod(), path)
          && !"MASTER".equals(jwt.getClaimAsString("role"))) {
        return forbidden(exchange.getResponse());
      }
      return chain.filter(exchange);
    } catch (JwtException | IllegalArgumentException exception) {
      return unauthorized(exchange.getResponse());
    }
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
}
