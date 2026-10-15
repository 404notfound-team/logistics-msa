package com.notfound.logistics.hub.presentation.exception.hubroute;

import com.notfound.logistics.common.api.ApiErrorResponse;
import com.notfound.logistics.hub.domain.exception.hubroute.HubRouteBusinessException;
import com.notfound.logistics.hub.presentation.controller.hubroute.HubRouteController;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(0)
@RestControllerAdvice(basePackageClasses = HubRouteController.class)
public class HubRouteExceptionHandler {
  @ExceptionHandler(HubRouteBusinessException.class)
  public ResponseEntity<ApiErrorResponse> handleHubRouteBusinessException(
      HubRouteBusinessException exception) {
    var errorCode = new HubRouteApiErrorCode(exception.getErrorCode());
    return ResponseEntity.status(errorCode.httpStatus())
        .body(ApiErrorResponse.of(errorCode, MDC.get("traceId")));
  }
}
