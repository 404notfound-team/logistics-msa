package com.notfound.logistics.hub.domain.exception.hub;

import java.util.Objects;

public class HubBusinessException extends RuntimeException {

  private final HubErrorCode errorCode;

  public HubBusinessException(HubErrorCode errorCode) {
    super(Objects.requireNonNull(errorCode, "errorCode").message());
    this.errorCode = errorCode;
  }

  public HubErrorCode getErrorCode() {
    return errorCode;
  }
}
