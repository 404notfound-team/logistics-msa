package com.notfound.logistics.orders.global.exception;

import java.util.Objects;

public class OrderBusinessException extends RuntimeException {
  private final OrderErrorCode errorCode;

  public OrderBusinessException(OrderErrorCode errorCode) {
    super(Objects.requireNonNull(errorCode, "errorCode").message());
    this.errorCode = errorCode;
  }

  public OrderErrorCode getErrorCode() {
    return errorCode;
  }
}
