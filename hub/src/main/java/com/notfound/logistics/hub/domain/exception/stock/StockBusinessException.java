package com.notfound.logistics.hub.domain.exception.stock;

import java.util.Objects;
import lombok.Getter;

@Getter
public class StockBusinessException extends RuntimeException {

  private final StockErrorCode errorCode;

  public StockBusinessException(StockErrorCode errorCode) {
    super(Objects.requireNonNull(errorCode, "errorCode").message());
    this.errorCode = errorCode;
  }
}
