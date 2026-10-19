package com.notfound.logistics.delivery.presentation.exception;

import com.notfound.logistics.common.api.ApiErrorCode;
import com.notfound.logistics.delivery.domain.exception.DeliveryErrorCode;

record DeliveryApiErrorCode(DeliveryErrorCode errorCode) implements ApiErrorCode {
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
      case STATUS_CHANGE_FORBIDDEN, ASSIGNMENT_FORBIDDEN, DELIVERY_READ_FORBIDDEN -> 403;
      case DELIVERY_NOT_FOUND, HUB_NOT_FOUND, HUB_ROUTE_NOT_FOUND -> 404;
      case ASSIGNMENT_NOT_ALLOWED,
          INVALID_STATUS_TRANSITION,
          CONCURRENT_MODIFICATION,
          DELIVERY_ALREADY_EXISTS,
          DELIVERY_ALREADY_STARTED,
          DELIVERY_IN_PROGRESS ->
          409;
      case HUB_UNAVAILABLE -> 503;
    };
  }
}
