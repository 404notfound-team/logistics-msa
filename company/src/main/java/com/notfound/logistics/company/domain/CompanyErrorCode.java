package com.notfound.logistics.company.domain;

import com.notfound.logistics.common.api.ApiErrorCode;

public enum CompanyErrorCode implements ApiErrorCode {
  COMPANY_NOT_FOUND(404, "업체를 찾을 수 없습니다."),
  HUB_NOT_FOUND(404, "허브를 찾을 수 없습니다."),
  COMPANY_ACCESS_DENIED(403, "업체에 대한 권한이 없습니다.");

  private final int httpStatus;
  private final String message;

  CompanyErrorCode(int httpStatus, String message) {
    this.httpStatus = httpStatus;
    this.message = message;
  }

  @Override
  public String code() {
    return name();
  }

  @Override
  public String message() {
    return message;
  }

  @Override
  public int httpStatus() {
    return httpStatus;
  }
}
