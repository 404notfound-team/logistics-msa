package com.notfound.logistics.hub.domain.hub.domain.exception;

import com.notfound.logistics.common.api.ApiErrorCode;

public enum HubErrorCode implements ApiErrorCode {
  HUB_NOT_FOUND(404, "허브를 찾을 수 없습니다."),
  HUB_ROUTE_NOT_FOUND(404, "허브 간 기준 이동정보를 찾을 수 없습니다."),
  STOCK_NOT_FOUND(404, "재고를 찾을 수 없습니다."),
  STOCK_OPERATION_NOT_FOUND(404, "재고 작업 기록을 찾을 수 없습니다."),

  INSUFFICIENT_STOCK(409, "재고가 부족합니다."),
  STOCK_OPERATION_CONFLICT(409, "기존 주문의 재고 작업과 요청 내용이 일치하지 않습니다."),
  STOCK_ALREADY_RESTORED(409, "이미 복원된 주문은 다시 차감할 수 없습니다.");

  private final int status;
  private final String description;

  HubErrorCode(int status, String description) {
    this.status = status;
    this.description = description;
  }

  @Override
  public String code() {
    return name();
  }

  @Override
  public String message() {
    return description;
  }

  @Override
  public int httpStatus() {
    return status;
  }
}
