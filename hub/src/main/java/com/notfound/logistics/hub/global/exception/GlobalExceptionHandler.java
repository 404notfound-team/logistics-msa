package com.notfound.logistics.hub.global.exception;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.notfound.logistics.common.api.ApiErrorCode;
import com.notfound.logistics.common.api.ApiErrorResponse;
import com.notfound.logistics.common.api.CommonErrorCode;
import com.notfound.logistics.common.api.FieldViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  // 허브 서비스의 비즈니스 예외
  @ExceptionHandler(HubBusinessException.class)
  public ResponseEntity<ApiErrorResponse> business(HubBusinessException exception) {
    return error(exception.getErrorCode());
  }

  // @Valid DTO 검증 실패
  @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
  public ResponseEntity<ApiErrorResponse> validation(BindException exception) {

    List<FieldViolation> fields = new ArrayList<>();

    for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
      fields.add(
          new FieldViolation(
              externalFieldName(fieldError.getField()),
              safeValidationMessage(fieldError.getDefaultMessage())));
    }

    for (ObjectError objectError : exception.getBindingResult().getGlobalErrors()) {
      fields.add(new FieldViolation("$", safeValidationMessage(objectError.getDefaultMessage())));
    }

    return ResponseEntity.badRequest().body(ApiErrorResponse.validation(traceId(), fields));
  }

  // 파라미터 제약조건 위반
  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ApiErrorResponse> constraint(ConstraintViolationException exception) {
    List<FieldViolation> fields =
        exception.getConstraintViolations().stream()
            .map(v -> new FieldViolation("$", safeValidationMessage(v.getMessage())))
            .toList();

    return ResponseEntity.badRequest().body(ApiErrorResponse.validation(traceId(), fields));
  }

  // JSON 형식 오류, UUID 타입 오류, 필수 파라미터 누락 등
  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    MissingServletRequestParameterException.class
  })
  public ResponseEntity<ApiErrorResponse> invalidRequest(Exception exception) {
    return error(CommonErrorCode.INVALID_REQUEST);
  }

  // 지원하지 않는 HTTP 메서드
  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<ApiErrorResponse> methodNotAllowed(Exception exception) {
    return error(CommonErrorCode.METHOD_NOT_ALLOWED);
  }

  // 지원하지 않는 Content-Type
  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<ApiErrorResponse> unsupportedMediaType(Exception exception) {
    return error(CommonErrorCode.UNSUPPORTED_MEDIA_TYPE);
  }

  // 존재하지 않는 API 경로
  @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
  public ResponseEntity<ApiErrorResponse> notFound(Exception exception) {
    return error(CommonErrorCode.NOT_FOUND);
  }

  // 예상하지 못한 서버 예외
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiErrorResponse> unexpected(Exception exception) {
    LOG.error("Unhandled hub request failure", exception);
    return error(CommonErrorCode.INTERNAL_ERROR);
  }

  @ExceptionHandler(HandlerMethodValidationException.class)
  public ResponseEntity<ApiErrorResponse> handleMethodValidation(
      HandlerMethodValidationException exception) {

    if (exception.isForReturnValue()) {
      LOG.error("Hub controller return value validation failed", exception);
      return error(CommonErrorCode.INTERNAL_ERROR);
    }

    List<FieldViolation> fields = new ArrayList<>();

    for (ParameterValidationResult result : exception.getParameterValidationResults()) {
      String name = parameterName(result.getMethodParameter());

      for (var violation : result.getResolvableErrors()) {
        fields.add(new FieldViolation(name, safeValidationMessage(violation.getDefaultMessage())));
      }
    }

    return ResponseEntity.badRequest().body(ApiErrorResponse.validation(traceId(), fields));
  }

  private static ResponseEntity<ApiErrorResponse> error(ApiErrorCode code) {
    return ResponseEntity.status(code.httpStatus()).body(ApiErrorResponse.of(code, traceId()));
  }

  private static String traceId() {
    return MDC.get("traceId");
  }

  private static String safeValidationMessage(String ignored) {
    // 검증 메시지에 사용자 입력값이 포함될 수 있으므로 직접 노출하지 않는다.
    return "입력값을 확인해 주세요.";
  }

  private static String parameterName(MethodParameter parameter) {
    RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);

    if (requestParam != null && !requestParam.name().isBlank()) {
      return requestParam.name();
    }

    PathVariable pathVariable = parameter.getParameterAnnotation(PathVariable.class);

    if (pathVariable != null && !pathVariable.name().isBlank()) {
      return pathVariable.name();
    }

    String name = parameter.getParameterName();
    return name == null ? "$" : externalFieldName(name);
  }

  private static String externalFieldName(String field) {
    if (field == null || field.isBlank()) {
      return "$";
    }

    String[] segments = field.split("\\.", -1);

    for (int i = 0; i < segments.length; i++) {
      String segment = segments[i];
      int bracketIndex = segment.indexOf('[');

      String property = bracketIndex >= 0 ? segment.substring(0, bracketIndex) : segment;
      String suffix = bracketIndex >= 0 ? segment.substring(bracketIndex) : "";

      segments[i] =
          ((PropertyNamingStrategies.NamingBase) PropertyNamingStrategies.SNAKE_CASE)
                  .translate(property)
              + suffix;
    }

    return String.join(".", segments);
  }
}
