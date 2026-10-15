package com.notfound.logistics.users.exception;

import com.notfound.logistics.common.api.ApiErrorCode;

public enum UserErrorCode implements ApiErrorCode {
  USERNAME_ALREADY_EXISTS(409, "이미 사용 중인 아이디입니다."),
  AFFILIATION_NOT_FOUND(404, "입력한 업체 또는 허브를 찾을 수 없습니다."),
  AFFILIATION_NAME_AMBIGUOUS(409, "같은 이름의 업체 또는 허브가 여러 개입니다."),
  AFFILIATION_SERVICE_UNAVAILABLE(503, "업체 또는 허브 정보를 확인할 수 없습니다."),
  USER_NOT_FOUND(404, "사용자를 찾을 수 없습니다.");

  private final int httpStatus;
  private final String message;

  UserErrorCode(int httpStatus, String message) {
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
