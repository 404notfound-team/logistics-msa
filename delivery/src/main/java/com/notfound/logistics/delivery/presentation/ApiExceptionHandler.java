package com.notfound.logistics.delivery.presentation;

import com.notfound.logistics.common.api.ApiErrorCode;
import com.notfound.logistics.common.api.ApiErrorResponse;
import com.notfound.logistics.common.api.CommonErrorCode;
import com.notfound.logistics.common.api.FieldViolation;
import io.micrometer.tracing.Tracer;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.MethodParameter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
class ApiExceptionHandler {
  private final ObjectProvider<Tracer> tracer;

  @ExceptionHandler(InvalidRequestException.class)
  ResponseEntity<ApiErrorResponse> invalidRequest(InvalidRequestException exception) {
    return validation(List.of(new FieldViolation(exception.field(), exception.getMessage())));
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<ApiErrorResponse> typeMismatch(MethodArgumentTypeMismatchException exception) {
    return validation(List.of(new FieldViolation(exception.getName(), "형식이 올바르지 않습니다.")));
  }

  @ExceptionHandler(HandlerMethodValidationException.class)
  ResponseEntity<ApiErrorResponse> methodValidation(HandlerMethodValidationException exception) {
    List<FieldViolation> violations =
        exception.getParameterValidationResults().stream()
            .flatMap(
                result -> {
                  String field = requestParameterName(result.getMethodParameter());
                  return result.getResolvableErrors().stream()
                      .map(error -> new FieldViolation(field, error.getDefaultMessage()));
                })
            .toList();
    return validation(violations);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiErrorResponse> unexpected(Exception exception) {
    // Framework 4xx/5xx(지원하지 않는 메서드, 없는 경로 등)는 상태를 유지하고 공통 오류 코드로 응답한다.
    if (exception instanceof ErrorResponse errorResponse) {
      int status = errorResponse.getStatusCode().value();
      CommonErrorCode error =
          Arrays.stream(CommonErrorCode.values())
              .filter(code -> code.httpStatus() == status)
              .findFirst()
              .orElse(
                  status < 500 ? CommonErrorCode.INVALID_REQUEST : CommonErrorCode.INTERNAL_ERROR);
      if (status >= 500) {
        log.error("Request failed", exception);
      }
      return error(status, error);
    }
    log.error("Unexpected error", exception);
    return error(500, CommonErrorCode.INTERNAL_ERROR);
  }

  private ResponseEntity<ApiErrorResponse> validation(List<FieldViolation> violations) {
    return ResponseEntity.badRequest().body(ApiErrorResponse.validation(traceId(), violations));
  }

  private ResponseEntity<ApiErrorResponse> error(int status, ApiErrorCode error) {
    return ResponseEntity.status(status).body(ApiErrorResponse.of(error, traceId()));
  }

  private static String requestParameterName(MethodParameter parameter) {
    RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
    if (requestParam != null && !requestParam.name().isEmpty()) {
      return requestParam.name();
    }
    return parameter.getParameterName();
  }

  private String traceId() {
    Tracer current = tracer.getIfAvailable();
    if (current == null || current.currentSpan() == null) {
      return null;
    }
    return current.currentSpan().context().traceId();
  }
}
