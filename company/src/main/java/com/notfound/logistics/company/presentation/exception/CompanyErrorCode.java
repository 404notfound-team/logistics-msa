package com.notfound.logistics.company.presentation.exception;

import com.notfound.logistics.common.api.ApiErrorCode;
import com.notfound.logistics.company.domain.exception.CompanyError;

/** 도메인 업무 오류(CompanyError)를 HTTP 상태와 응답 메시지로 변환한다. */
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

  // switch로 매핑해 CompanyError에 값이 추가되면 컴파일 단계에서 누락을 알 수 있게 한다.
  public static CompanyErrorCode from(CompanyError error) {
    return switch (error) {
      case COMPANY_NOT_FOUND -> COMPANY_NOT_FOUND;
      case HUB_NOT_FOUND -> HUB_NOT_FOUND;
      case COMPANY_ACCESS_DENIED -> COMPANY_ACCESS_DENIED;
    };
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
