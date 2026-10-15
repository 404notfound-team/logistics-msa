package com.notfound.logistics.users.exception;

import com.notfound.logistics.common.api.ApiErrorCode;

public class UserException extends RuntimeException {
  private final ApiErrorCode errorCode;

  public UserException(ApiErrorCode errorCode) {
    super(errorCode.message());
    this.errorCode = errorCode;
  }

  public ApiErrorCode getErrorCode() {
    return errorCode;
  }
}
