package com.notfound.logistics.delivery.presentation.exception;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.notfound.logistics.common.api.ApiErrorCode;
import com.notfound.logistics.common.api.ApiErrorResponse;
import com.notfound.logistics.common.api.CommonErrorCode;
import com.notfound.logistics.common.api.FieldViolation;
import com.notfound.logistics.delivery.domain.exception.DeliveryBusinessException;
import io.micrometer.tracing.Tracer;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
class ApiExceptionHandler {
  // Java 필드명(orderId)을 요청 JSON의 이름(order_id)으로 바꾼다. spring.jackson의 SNAKE_CASE 설정과 같다.
  private static final PropertyNamingStrategies.NamingBase SNAKE_CASE =
      (PropertyNamingStrategies.NamingBase) PropertyNamingStrategies.SNAKE_CASE;

  private final ObjectProvider<Tracer> tracer;

  @ExceptionHandler(DeliveryBusinessException.class)
  ResponseEntity<ApiErrorResponse> business(DeliveryBusinessException exception) {
    DeliveryApiErrorCode error = new DeliveryApiErrorCode(exception.getErrorCode());
    return error(error.httpStatus(), HttpHeaders.EMPTY, error);
  }

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

  // @Valid @RequestBody 검증 실패. 어느 필드가 틀렸는지 요청 본문과 같은 snake_case 이름으로 알려준다.
  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ApiErrorResponse> bodyValidation(MethodArgumentNotValidException exception) {
    List<FieldViolation> violations =
        exception.getBindingResult().getFieldErrors().stream()
            .map(
                error ->
                    new FieldViolation(
                        SNAKE_CASE.translate(error.getField()), error.getDefaultMessage()))
            .toList();
    return validation(violations);
  }

  // 요청 본문을 읽을 수 없음(본문 없음, JSON 문법 오류, UUID 등 형식 오류). 500이 아니라 400으로 응답한다.
  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ApiErrorResponse> unreadableBody(HttpMessageNotReadableException exception) {
    return error(400, HttpHeaders.EMPTY, CommonErrorCode.INVALID_REQUEST);
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
      return error(status, errorResponse.getHeaders(), error);
    }
    log.error("Unexpected error", exception);
    return error(500, HttpHeaders.EMPTY, CommonErrorCode.INTERNAL_ERROR);
  }

  private ResponseEntity<ApiErrorResponse> validation(List<FieldViolation> violations) {
    return ResponseEntity.badRequest().body(ApiErrorResponse.validation(traceId(), violations));
  }

  // 405의 Allow, 415의 Accept 같은 프레임워크 응답 헤더를 유지한다.
  private ResponseEntity<ApiErrorResponse> error(
      int status, HttpHeaders headers, ApiErrorCode error) {
    return ResponseEntity.status(status)
        .headers(headers)
        .body(ApiErrorResponse.of(error, traceId()));
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
