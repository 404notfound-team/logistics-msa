package com.notfound.logistics.gateway.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notfound.logistics.common.api.ApiErrorCode;
import com.notfound.logistics.common.api.ApiErrorResponse;
import com.notfound.logistics.common.api.CommonErrorCode;
import io.micrometer.tracing.handler.TracingObservationHandler.TracingContext;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Gateway의 인증·인가 오류를 공통 오류 형식으로 응답합니다. */
@Component
public class ApiErrorResponseWriter {
  private final ObjectMapper objectMapper;

  public ApiErrorResponseWriter(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /** Users 조회 실패는 503, 그 밖의 인증 실패는 401입니다. */
  public ServerAuthenticationEntryPoint authenticationEntryPoint() {
    return (exchange, exception) ->
        write(
            exchange,
            exception instanceof UsersAuthorizationClient.UsersUnavailableException
                ? CommonErrorCode.SERVICE_UNAVAILABLE
                : CommonErrorCode.UNAUTHORIZED);
  }

  public ServerAccessDeniedHandler accessDeniedHandler() {
    return (exchange, exception) -> write(exchange, CommonErrorCode.FORBIDDEN);
  }

  private Mono<Void> write(ServerWebExchange exchange, ApiErrorCode errorCode) {
    ServerHttpResponse response = exchange.getResponse();
    String traceId = traceId(exchange);
    response.setStatusCode(HttpStatusCode.valueOf(errorCode.httpStatus()));
    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
    byte[] body;
    try {
      body = objectMapper.writeValueAsBytes(ApiErrorResponse.of(errorCode, traceId));
    } catch (JsonProcessingException exception) {
      body =
          ("{\"code\":\""
                  + errorCode.code()
                  + "\",\"message\":\""
                  + errorCode.message()
                  + "\",\"trace_id\":"
                  + (traceId == null ? "null" : "\"" + traceId + "\"")
                  + ",\"field_errors\":[]}")
              .getBytes(StandardCharsets.UTF_8);
    }
    return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
  }

  /**
   * WebFlux는 처리 중 스레드가 바뀌어 MDC를 믿을 수 없으므로, 요청에 붙은 서버 observation의 span에서 읽습니다. tracing이 꺼져 있으면
   * null입니다.
   */
  private static String traceId(ServerWebExchange exchange) {
    return ServerRequestObservationContext.findCurrent(exchange.getAttributes())
        .map(context -> context.<TracingContext>get(TracingContext.class))
        .map(TracingContext::getSpan)
        .map(span -> span.context().traceId())
        .orElse(null);
  }
}
