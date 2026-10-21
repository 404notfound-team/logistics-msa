package com.notfound.logistics.hub.presentation.exception.hub;

import com.notfound.logistics.common.api.ApiErrorCode;
import com.notfound.logistics.hub.domain.exception.hub.HubErrorCode;

public record HubApiErrorCode(HubErrorCode error) implements ApiErrorCode {

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
      case HUB_NOT_FOUND, HUB_ROUTE_NOT_FOUND, STOCK_NOT_FOUND, STOCK_OPERATION_NOT_FOUND -> 404;
      case INSUFFICIENT_STOCK, STOCK_OPERATION_CONFLICT, STOCK_ALREADY_RESTORED -> 409;
    };
  }
}
