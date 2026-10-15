package com.notfound.logistics.hub.domain.hub.domain.exception;

import com.notfound.logistics.common.api.ApiErrorCode;
import java.util.Objects;

public class HubBusinessException extends RuntimeException {

  private final ApiErrorCode errorCode;

  public HubBusinessException(ApiErrorCode errorCode) {
    super(Objects.requireNonNull(errorCode, "errorCode").message());
    this.errorCode = errorCode;
  }

  public ApiErrorCode getErrorCode() {
    return errorCode;
  }
}
