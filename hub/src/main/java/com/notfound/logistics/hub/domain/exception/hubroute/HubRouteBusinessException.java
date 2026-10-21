package com.notfound.logistics.hub.domain.exception.hubroute;

import java.util.Objects;

public class HubRouteBusinessException extends RuntimeException {
  private final HubRouteErrorCode errorCode;

  public HubRouteBusinessException(HubRouteErrorCode errorCode) {
    super(Objects.requireNonNull(errorCode, "errorCode").message());
    this.errorCode = errorCode;
  }

  public HubRouteErrorCode getErrorCode() {
    return errorCode;
  }
}
