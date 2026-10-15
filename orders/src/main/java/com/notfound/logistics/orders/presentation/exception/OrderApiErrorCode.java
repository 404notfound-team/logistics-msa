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
      case ORDER_NOT_FOUND -> 404;
      case ORDER_STATE_CONFLICT -> 409;
    };
  }
}
