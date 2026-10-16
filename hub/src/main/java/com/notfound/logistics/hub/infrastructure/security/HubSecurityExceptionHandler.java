package com.notfound.logistics.hub.infrastructure.security;

import com.notfound.logistics.common.api.ApiErrorResponse;
import com.notfound.logistics.common.api.CommonErrorCode;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class HubSecurityExceptionHandler {
  @ExceptionHandler(AuthenticationException.class)
  public ResponseEntity<ApiErrorResponse> unauthorized(AuthenticationException exception) {
    return error(CommonErrorCode.UNAUTHORIZED);
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ApiErrorResponse> forbidden(AccessDeniedException exception) {
    return error(CommonErrorCode.FORBIDDEN);
  }

  private ResponseEntity<ApiErrorResponse> error(CommonErrorCode code) {
    return ResponseEntity.status(code.httpStatus())
        .body(ApiErrorResponse.of(code, MDC.get("traceId")));
  }
}
