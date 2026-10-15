package com.notfound.logistics.hub.domain.exception.hub;

public enum HubErrorCode {
  HUB_NOT_FOUND("허브를 찾을 수 없습니다."),
  HUB_ROUTE_NOT_FOUND("허브 간 기준 이동정보를 찾을 수 없습니다."),
  STOCK_NOT_FOUND("재고를 찾을 수 없습니다."),
  STOCK_OPERATION_NOT_FOUND("재고 작업 기록을 찾을 수 없습니다."),

  INSUFFICIENT_STOCK("재고가 부족합니다."),
  STOCK_OPERATION_CONFLICT("기존 주문의 재고 작업과 요청 내용이 일치하지 않습니다."),
  STOCK_ALREADY_RESTORED("이미 복원된 주문은 다시 차감할 수 없습니다.");

  private final String description;

  HubErrorCode(String description) {

    this.description = description;
  }

  public String code() {
    return name();
  }

  public String message() {
    return description;
  }
}
