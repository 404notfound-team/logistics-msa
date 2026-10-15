package com.notfound.logistics.hub.presentation.exception.hubroute;

import com.notfound.logistics.common.api.ApiErrorCode;
import com.notfound.logistics.hub.domain.exception.hubroute.HubRouteErrorCode;

public record HubRouteApiErrorCode(HubRouteErrorCode error) implements ApiErrorCode {
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
      case INVALID_HUB_ROUTE_DURATION, INVALID_HUB_ROUTE_DISTANCE, INVALID_SAME_HUB_ROUTE -> 400;
    };
  }
}
