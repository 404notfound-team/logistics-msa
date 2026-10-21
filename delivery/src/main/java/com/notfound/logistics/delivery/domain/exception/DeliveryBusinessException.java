package com.notfound.logistics.delivery.domain.exception;

import java.util.Objects;

public class DeliveryBusinessException extends RuntimeException {
  private final DeliveryErrorCode errorCode;

  public DeliveryBusinessException(DeliveryErrorCode errorCode) {
    super(Objects.requireNonNull(errorCode, "errorCode").message());
    this.errorCode = errorCode;
  }

  public DeliveryErrorCode getErrorCode() {
    return errorCode;
  }
}
