package com.notfound.logistics.hub.presentation.exception.hub;

import com.notfound.logistics.common.api.ApiErrorResponse;
import com.notfound.logistics.hub.domain.exception.hub.HubBusinessException;
import com.notfound.logistics.hub.presentation.controller.hub.HubController;
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

    var errorCode = new HubApiErrorCode(exception.getErrorCode());

    return ResponseEntity.status(errorCode.httpStatus())
        .body(ApiErrorResponse.of(errorCode, MDC.get("traceId")));
  }
}
