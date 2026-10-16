package com.notfound.logistics.users.presentation.exception;

import com.notfound.logistics.common.api.ApiErrorCode;

public enum UserErrorCode implements ApiErrorCode {
  USERNAME_ALREADY_EXISTS(409, "이미 사용 중인 아이디입니다."),
  INVALID_CREDENTIALS(401, "아이디 또는 비밀번호가 올바르지 않습니다."),
  ENTITY_NOT_FOUND(404, "입력한 업체 또는 허브를 찾을 수 없습니다."),
  INVALID_AFFILIATION(400, "일반 사용자는 업체 또는 허브 중 하나에만, 마스터는 최대 하나에 소속될 수 있습니다."),
  INVALID_SORT(400, "지원하지 않는 정렬 조건입니다."),
  AFFILIATION_AMBIGUOUS(409, "같은 이름의 업체 또는 허브가 여러 개입니다."),
  AFFILIATION_LOOKUP_UNAVAILABLE(503, "업체 또는 허브 정보를 확인할 수 없습니다."),
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
