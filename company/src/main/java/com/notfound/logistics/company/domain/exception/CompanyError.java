package com.notfound.logistics.company.domain.exception;

/** 업체 도메인의 업무 오류. HTTP 상태와 응답 메시지는 presentation.exception.CompanyErrorCode가 정한다. */
public enum CompanyError {
  COMPANY_NOT_FOUND,
  PRODUCT_NOT_FOUND,
  HUB_NOT_FOUND,
  COMPANY_ACCESS_DENIED
}
