package com.notfound.logistics.users.presentation.exception;

import com.notfound.logistics.common.api.ApiErrorCode;
import com.notfound.logistics.common.api.ApiErrorResponse;
import com.notfound.logistics.common.api.CommonErrorCode;
import com.notfound.logistics.common.api.FieldViolation;
import com.notfound.logistics.users.domain.exception.UserException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class UserExceptionHandler {
  private static final Logger LOG = LoggerFactory.getLogger(UserExceptionHandler.class);

  @ExceptionHandler(UserException.class)
  public ResponseEntity<ApiErrorResponse> handleUserException(UserException exception) {
    var error = exception.getErrorCode();
    return ResponseEntity.status(HttpStatusCode.valueOf(error.httpStatus()))
        .body(ApiErrorResponse.of(error, traceId()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiErrorResponse> handleValidation(
      MethodArgumentNotValidException exception) {
    List<FieldViolation> violations =
        exception.getBindingResult().getFieldErrors().stream()
            .map(this::toViolation)
            .collect(Collectors.toCollection(ArrayList::new));
    exception.getBindingResult().getGlobalErrors().stream()
        .map(
            error ->
                new FieldViolation(
                    "$",
                    error.getDefaultMessage() == null
                        ? CommonErrorCode.INVALID_REQUEST.message()
                        : error.getDefaultMessage()))
        .forEach(violations::add);
    return ResponseEntity.badRequest().body(ApiErrorResponse.validation(traceId(), violations));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiErrorResponse> handleUnreadableRequest(
      HttpMessageNotReadableException exception) {
    return ResponseEntity.badRequest()
        .body(ApiErrorResponse.of(CommonErrorCode.INVALID_REQUEST, traceId()));
  }

  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ApiErrorResponse> handleNotFound(NoResourceFoundException exception) {
    return ResponseEntity.status(HttpStatusCode.valueOf(CommonErrorCode.NOT_FOUND.httpStatus()))
        .headers(exception.getHeaders())
        .body(ApiErrorResponse.of(CommonErrorCode.NOT_FOUND, traceId()));
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<ApiErrorResponse> handleMethodNotAllowed(
      HttpRequestMethodNotSupportedException exception) {
    return ResponseEntity.status(CommonErrorCode.METHOD_NOT_ALLOWED.httpStatus())
        .headers(exception.getHeaders())
        .body(ApiErrorResponse.of(CommonErrorCode.METHOD_NOT_ALLOWED, traceId()));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception) {
    if (exception instanceof ErrorResponse webException
        && webException.getStatusCode().is4xxClientError()) {
      HttpStatusCode status = webException.getStatusCode();
      return ResponseEntity.status(status)
          .headers(webException.getHeaders())
          .body(ApiErrorResponse.of(errorCode(status), traceId()));
    }
    LOG.error("Unexpected error while handling users API request", exception);
    return ResponseEntity.internalServerError()
        .body(ApiErrorResponse.of(CommonErrorCode.INTERNAL_ERROR, traceId()));
  }

  private FieldViolation toViolation(FieldError error) {
    String field =
        "exactlyOneAffiliationProvided".equals(error.getField())
            ? "$"
            : toSnakeCase(error.getField());
    String message =
        error.getDefaultMessage() == null
            ? CommonErrorCode.INVALID_REQUEST.message()
            : error.getDefaultMessage();
    return new FieldViolation(field, message);
  }

  private String toSnakeCase(String field) {
    return field.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
  }

  private ApiErrorCode errorCode(HttpStatusCode status) {
    return switch (status.value()) {
      case 400 -> CommonErrorCode.INVALID_REQUEST;
      case 401 -> CommonErrorCode.UNAUTHORIZED;
      case 403 -> CommonErrorCode.FORBIDDEN;
      case 404 -> CommonErrorCode.NOT_FOUND;
      case 405 -> CommonErrorCode.METHOD_NOT_ALLOWED;
      case 409 -> CommonErrorCode.CONFLICT;
      case 415 -> CommonErrorCode.UNSUPPORTED_MEDIA_TYPE;
      default ->
          new ApiErrorCode() {
            @Override
            public String code() {
              return "HTTP_" + status.value();
            }

            @Override
            public String message() {
              return "요청을 처리할 수 없습니다.";
            }

            @Override
            public int httpStatus() {
              return status.value();
            }
          };
    };
  }

  private String traceId() {
    return MDC.get("traceId");
  }
}
