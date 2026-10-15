package com.notfound.logistics.orders.global.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notfound.logistics.common.api.ApiErrorResponse;
import com.notfound.logistics.common.api.CommonErrorCode;
import com.notfound.logistics.common.api.FieldViolation;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.Errors;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
  private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);
  private static final String INVALID_FIELD = "올바른 값을 입력해 주세요.";
  private final ValidationFieldNames names;

  public GlobalExceptionHandler(ObjectMapper mapper) {
    names = new ValidationFieldNames(mapper);
  }

  @ExceptionHandler(OrderBusinessException.class)
  ResponseEntity<Object> business(OrderBusinessException exception) {
    var code = exception.getErrorCode();
    return ResponseEntity.status(code.httpStatus()).body(ApiErrorResponse.of(code, traceId()));
  }

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException exception,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    return super.handleExceptionInternal(
        exception,
        ApiErrorResponse.validation(
            traceId(),
            fields(
                exception.getBindingResult(),
                exception.getParameter().hasParameterAnnotation(RequestBody.class)
                    || exception.getParameter().hasParameterAnnotation(RequestPart.class))),
        headers,
        status,
        request);
  }

  @ExceptionHandler(BindException.class)
  ResponseEntity<Object> binding(BindException exception) {
    return ResponseEntity.badRequest()
        .body(ApiErrorResponse.validation(traceId(), fields(exception.getBindingResult(), false)));
  }

  @Override
  protected ResponseEntity<Object> handleHandlerMethodValidationException(
      HandlerMethodValidationException exception,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    if (exception.isForReturnValue()) {
      return handleExceptionInternal(exception, null, headers, status, request);
    }
    var fields = new ArrayList<FieldViolation>();
    for (var result : exception.getParameterValidationResults()) {
      if (result instanceof ParameterErrors errors) {
        var parameter = result.getMethodParameter();
        fields.addAll(
            fields(
                errors,
                parameter.hasParameterAnnotation(RequestBody.class)
                    || parameter.hasParameterAnnotation(RequestPart.class)));
      } else {
        result
            .getResolvableErrors()
            .forEach(
                ignored -> fields.add(violation(names.parameter(result.getMethodParameter()))));
      }
    }
    exception.getCrossParameterValidationResults().forEach(ignored -> fields.add(violation("$")));
    return super.handleExceptionInternal(
        exception, ApiErrorResponse.validation(traceId(), fields), headers, status, request);
  }

  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception exception,
      Object body,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    if (status.is5xxServerError()) {
      LOG.error("Order request failed; traceId={}", traceId(), exception);
    }
    var code =
        switch (status.value()) {
          case 401 -> CommonErrorCode.UNAUTHORIZED;
          case 403 -> CommonErrorCode.FORBIDDEN;
          case 404 -> CommonErrorCode.NOT_FOUND;
          case 405 -> CommonErrorCode.METHOD_NOT_ALLOWED;
          case 409 -> CommonErrorCode.CONFLICT;
          case 415 -> CommonErrorCode.UNSUPPORTED_MEDIA_TYPE;
          case 503 -> CommonErrorCode.SERVICE_UNAVAILABLE;
          default ->
              status.is4xxClientError()
                  ? CommonErrorCode.INVALID_REQUEST
                  : CommonErrorCode.INTERNAL_ERROR;
        };
    return super.handleExceptionInternal(
        exception, ApiErrorResponse.of(code, traceId()), headers, status, request);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Object> unexpected(Exception exception) {
    LOG.error("Order request failed; traceId={}", traceId(), exception);
    return ResponseEntity.internalServerError()
        .body(ApiErrorResponse.of(CommonErrorCode.INTERNAL_ERROR, traceId()));
  }

  private List<FieldViolation> fields(Errors errors, boolean jsonBody) {
    Object bindingTarget = errors instanceof BindingResult binding ? binding.getTarget() : null;
    Object target =
        errors instanceof ParameterErrors parameterErrors
            ? parameterErrors.getArgument()
            : bindingTarget;
    var fieldViolations =
        errors.getFieldErrors().stream()
            .map(
                error ->
                    violation(
                        jsonBody ? names.bodyField(target, error.getField()) : error.getField()));
    var globalViolations = errors.getGlobalErrors().stream().map(ignored -> violation("$"));
    return Stream.concat(fieldViolations, globalViolations).toList();
  }

  private FieldViolation violation(String field) {
    return new FieldViolation(field, INVALID_FIELD);
  }

  private String traceId() {
    return MDC.get("traceId");
  }
}
