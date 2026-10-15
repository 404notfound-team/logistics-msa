package com.notfound.logistics.hub.domain.hub.presentation.exception;

import com.notfound.logistics.common.api.ApiErrorResponse;
import com.notfound.logistics.hub.domain.hub.domain.exception.HubBusinessException;
import com.notfound.logistics.hub.domain.hub.presentation.HubController;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(0)
@RestControllerAdvice(basePackageClasses = HubController.class)
public class HubExceptionHandler {

  @ExceptionHandler(HubBusinessException.class)
  public ResponseEntity<ApiErrorResponse> handleHubBusinessException(
      HubBusinessException exception) {

    var errorCode = exception.getErrorCode();

    return ResponseEntity.status(errorCode.httpStatus())
        .body(ApiErrorResponse.of(errorCode, MDC.get("traceId")));
  }
}
