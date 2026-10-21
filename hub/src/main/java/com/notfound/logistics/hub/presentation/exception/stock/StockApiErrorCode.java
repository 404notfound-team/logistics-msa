package com.notfound.logistics.hub.presentation.exception.stock;

import com.notfound.logistics.common.api.ApiErrorCode;
import com.notfound.logistics.hub.domain.exception.stock.StockErrorCode;

public record StockApiErrorCode(StockErrorCode error) implements ApiErrorCode {

  @Override
  public String code() {
    return error.code();
  }

  @Override
  public String message() {
    return error.message();
  }

  @Override
  public int httpStatus() {
    return switch (error) {
      case INVALID_STOCK_QUANTITY, INVALID_INITIAL_STOCK -> 400;

      case STOCK_NOT_FOUND, PRODUCT_NOT_FOUND, STOCK_DEDUCTION_NOT_FOUND -> 404;

      case INSUFFICIENT_STOCK,
          STOCK_ALREADY_EXISTS,
          STOCK_QUANTITY_OVERFLOW,
          STOCK_DEDUCTION_CONFLICT,
          STOCK_DEDUCTION_REJECTED,
          STOCK_ALREADY_RESTORED,
          STOCK_RESTORATION_CONFLICT ->
          409;
    };
  }
}
