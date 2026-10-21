package com.notfound.logistics.common.api;

public enum CommonErrorCode implements ApiErrorCode {
  INVALID_REQUEST(400, "요청 값이 올바르지 않습니다."),
  UNAUTHORIZED(401, "인증이 필요합니다."),
  FORBIDDEN(403, "접근 권한이 없습니다."),
  NOT_FOUND(404, "요청한 리소스를 찾을 수 없습니다."),
  METHOD_NOT_ALLOWED(405, "지원하지 않는 요청 방식입니다."),
  CONFLICT(409, "현재 상태에서는 요청을 처리할 수 없습니다."),
  UNSUPPORTED_MEDIA_TYPE(415, "지원하지 않는 요청 형식입니다."),
  INTERNAL_ERROR(500, "요청 처리 중 오류가 발생했습니다."),
  SERVICE_UNAVAILABLE(503, "서비스를 일시적으로 사용할 수 없습니다.");

  private final int httpStatus;
  private final String message;

  CommonErrorCode(int httpStatus, String message) {
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
