package com.notfound.logistics.orders.presentation.exception;

import com.notfound.logistics.common.api.ApiErrorCode;
import com.notfound.logistics.orders.domain.exception.OrderErrorCode;

record OrderApiErrorCode(OrderErrorCode errorCode) implements ApiErrorCode {

  @Override
  public String code() {
    return errorCode.name();
  }

  @Override
  public String message() {
    return errorCode.message();
  }

  @Override
  public int httpStatus() {
    return switch (errorCode) {
      case INVALID_ORDER_INPUT -> 400;
      case AUTHENTICATION_REQUIRED -> 401;
      case ORDER_FORBIDDEN -> 403;
      case ORDER_NOT_FOUND, COMPANY_NOT_FOUND, PRODUCT_NOT_FOUND, STOCK_NOT_FOUND -> 404;
      case ORDER_STATE_CONFLICT,
          PRODUCT_SUPPLIER_MISMATCH,
          IDEMPOTENCY_KEY_REUSED,
          INSUFFICIENT_STOCK ->
          409;
      case DELIVERY_CREATION_FAILED -> 502;
      case UPSTREAM_UNAVAILABLE -> 503;
    };
  }
}
