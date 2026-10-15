package com.notfound.logistics.users.exception;

import com.notfound.logistics.common.api.ApiErrorResponse;
import com.notfound.logistics.common.api.CommonErrorCode;
import com.notfound.logistics.common.api.FieldViolation;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class UserExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(UserExceptionHandler.class);

  @ExceptionHandler(UserException.class)
  public ResponseEntity<ApiErrorResponse> handleUserException(UserException exception) {
    var error = exception.getErrorCode();
    return ResponseEntity.status(HttpStatusCode.valueOf(error.httpStatus()))
        .body(ApiErrorResponse.of(error, traceId()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
    List<FieldViolation> violations = exception.getBindingResult().getFieldErrors().stream()
        .map(this::toViolation)
        .collect(Collectors.toCollection(ArrayList::new));
    exception.getBindingResult().getGlobalErrors().stream()
        .map(error -> new FieldViolation("$", error.getDefaultMessage() == null
            ? CommonErrorCode.INVALID_REQUEST.message() : error.getDefaultMessage()))
        .forEach(violations::add);
    return ResponseEntity.badRequest().body(ApiErrorResponse.validation(traceId(), violations));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiErrorResponse> handleUnreadableRequest(HttpMessageNotReadableException exception) {
    return ResponseEntity.badRequest()
        .body(ApiErrorResponse.of(CommonErrorCode.INVALID_REQUEST, traceId()));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception) {
    log.error("Unexpected error while handling users API request", exception);
    return ResponseEntity.internalServerError()
        .body(ApiErrorResponse.of(CommonErrorCode.INTERNAL_ERROR, traceId()));
  }

  private FieldViolation toViolation(FieldError error) {
    String field = "exactlyOneAffiliationProvided".equals(error.getField()) ? "$" : error.getField();
    String message = error.getDefaultMessage() == null
        ? CommonErrorCode.INVALID_REQUEST.message() : error.getDefaultMessage();
    return new FieldViolation(field, message);
  }

  private String traceId() {
    return MDC.get("traceId");
  }
}
