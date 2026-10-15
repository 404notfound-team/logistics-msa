package com.notfound.logistics.company.presentation.exception;

import com.notfound.logistics.common.api.ApiErrorCode;
import com.notfound.logistics.common.api.ApiErrorResponse;
import com.notfound.logistics.common.api.CommonErrorCode;
import com.notfound.logistics.common.api.FieldViolation;
import com.notfound.logistics.company.domain.exception.CompanyException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** 전역 예외 처리 클래스입니다. 컨트롤러에서 발생하는 예외들을 일괄적으로 처리하여 일관된 응답을 제공합니다. */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

  /**
   * 업무 예외(CompanyException) 처리 서비스, 도메인 계층 등에서 발생한 비즈니스 예외를 처리합니다.
   *
   * @param e CompanyException (도메인 업무 오류를 담은 예외)
   * @return CompanyErrorCode로 변환한 HTTP 상태와 ApiErrorResponse 응답
   */
  @ExceptionHandler(CompanyException.class)
  ResponseEntity<ApiErrorResponse> handleCompany(CompanyException e) {
    ApiErrorCode code = CompanyErrorCode.from(e.getError());
    log.warn("업무 예외 발생: {}", code.code());
    return ResponseEntity.status(code.httpStatus()).body(ApiErrorResponse.of(code, traceId()));
  }

  /**
   * Valid 어노테이션 검증 실패 시 발생하는 예외 처리
   *
   * @param e MethodArgumentNotValidException
   * @return 모든 필드 오류를 field_errors 목록에 담은 ApiErrorResponse 응답 (HTTP 400 Bad Request)
   */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException e) {
    List<FieldViolation> violations = new ArrayList<>();
    e.getBindingResult()
        .getFieldErrors()
        .forEach(
            fe -> violations.add(violation(toSnakeCase(fe.getField()), fe.getDefaultMessage())));
    e.getBindingResult()
        .getGlobalErrors()
        .forEach(ge -> violations.add(violation("$", ge.getDefaultMessage())));
    return ResponseEntity.badRequest().body(ApiErrorResponse.validation(traceId(), violations));
  }

  // 잘못된 JSON, 본문 값의 타입 오류, 경로·쿼리 값 형식 오류, 필수 쿼리 누락
  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    MissingServletRequestParameterException.class
  })
  ResponseEntity<ApiErrorResponse> handleBadRequest(Exception e) {
    return error(CommonErrorCode.INVALID_REQUEST);
  }

  @ExceptionHandler(NoResourceFoundException.class)
  ResponseEntity<ApiErrorResponse> handleNotFound(NoResourceFoundException e) {
    return error(CommonErrorCode.NOT_FOUND);
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  ResponseEntity<ApiErrorResponse> handleMethodNotAllowed(
      HttpRequestMethodNotSupportedException e) {
    return error(CommonErrorCode.METHOD_NOT_ALLOWED);
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  ResponseEntity<ApiErrorResponse> handleUnsupportedMediaType(
      HttpMediaTypeNotSupportedException e) {
    return error(CommonErrorCode.UNSUPPORTED_MEDIA_TYPE);
  }

  // 상세 내용은 로그에만 남기고, 응답에는 내부 예외 정보를 노출하지 않는다.
  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiErrorResponse> handleUnexpected(Exception e) {
    log.error("Unhandled exception", e);
    return error(CommonErrorCode.INTERNAL_ERROR);
  }

  private ResponseEntity<ApiErrorResponse> error(ApiErrorCode code) {
    return ResponseEntity.status(code.httpStatus()).body(ApiErrorResponse.of(code, traceId()));
  }

  private static FieldViolation violation(String field, String message) {
    return new FieldViolation(field, message == null ? "올바르지 않은 값입니다." : message);
  }

  // Java 필드 이름(hubId)을 외부 JSON 이름(hub_id)으로 변환한다.
  private static String toSnakeCase(String field) {
    return field.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
  }

  // TODO: Micrometer Tracer의 현재 traceId를 넣어 Zipkin 추적과 연결한다. 계약상 null도 허용된다.
  private static String traceId() {
    return null;
  }
}
