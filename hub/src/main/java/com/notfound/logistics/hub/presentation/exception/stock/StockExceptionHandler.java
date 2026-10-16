package com.notfound.logistics.hub.presentation.exception.stock;

import com.notfound.logistics.common.api.ApiErrorResponse;
import com.notfound.logistics.common.api.CommonErrorCode;
import com.notfound.logistics.hub.domain.exception.hub.HubBusinessException;
import com.notfound.logistics.hub.domain.exception.stock.StockBusinessException;
import com.notfound.logistics.hub.domain.exception.stock.StockErrorCode;
import com.notfound.logistics.hub.infrastructure.client.ProductLookupException;
import com.notfound.logistics.hub.presentation.controller.stock.StockController;
import com.notfound.logistics.hub.presentation.exception.hub.HubApiErrorCode;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(0)
@RestControllerAdvice(basePackageClasses = StockController.class)
public class StockExceptionHandler {
  private static final Logger LOG = LoggerFactory.getLogger(StockExceptionHandler.class);

  @ExceptionHandler(ProductLookupException.class)
  public ResponseEntity<ApiErrorResponse> handleProductLookupFailure(
      ProductLookupException exception) {
    var code = CommonErrorCode.SERVICE_UNAVAILABLE;
    return ResponseEntity.status(code.httpStatus())
        .body(ApiErrorResponse.of(code, MDC.get("traceId")));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<ApiErrorResponse> handleIntegrityViolation(
      DataIntegrityViolationException exception) {
    for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
      if (cause instanceof ConstraintViolationException violation
          && "uk_stocks_hub_product".equals(violation.getConstraintName())) {
        var errorCode = new StockApiErrorCode(StockErrorCode.STOCK_ALREADY_EXISTS);
        return ResponseEntity.status(errorCode.httpStatus())
            .body(ApiErrorResponse.of(errorCode, MDC.get("traceId")));
      }
    }
    LOG.error("Unhandled stock integrity violation", exception);
    return ResponseEntity.internalServerError()
        .body(ApiErrorResponse.of(CommonErrorCode.INTERNAL_ERROR, MDC.get("traceId")));
  }

  @ExceptionHandler(StockBusinessException.class)
  public ResponseEntity<ApiErrorResponse> handleStockBusinessException(
      StockBusinessException exception) {

    var errorCode = new StockApiErrorCode(exception.getErrorCode());

    return ResponseEntity.status(errorCode.httpStatus())
        .body(ApiErrorResponse.of(errorCode, MDC.get("traceId")));
  }

  @ExceptionHandler(HubBusinessException.class)
  public ResponseEntity<ApiErrorResponse> handleHubBusinessException(
      HubBusinessException exception) {

    var errorCode = new HubApiErrorCode(exception.getErrorCode());

    return ResponseEntity.status(errorCode.httpStatus())
        .body(ApiErrorResponse.of(errorCode, MDC.get("traceId")));
  }
}
