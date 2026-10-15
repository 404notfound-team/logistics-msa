package com.notfound.logistics.company.domain;

import com.notfound.logistics.common.api.ApiErrorCode;

public class CompanyException extends RuntimeException {
  private final ApiErrorCode errorCode;

  public CompanyException(ApiErrorCode errorCode) {
    super(errorCode.message());
    this.errorCode = errorCode;
  }

  public ApiErrorCode getErrorCode() {
    return errorCode;
  }
}
