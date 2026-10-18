package com.notfound.logistics.delivery.domain.exception;

public enum DeliveryErrorCode {
  DELIVERY_NOT_FOUND("배송을 찾을 수 없습니다."),
  ASSIGNMENT_NOT_ALLOWED("허브 대기중인 배송만 담당자를 배정할 수 있습니다."),
  INVALID_STATUS_TRANSITION("현재 배송 상태에서 요청한 상태로 변경할 수 없습니다."),
  CONCURRENT_MODIFICATION("다른 요청이 배송을 먼저 변경했습니다. 다시 조회한 뒤 요청해 주세요.");

  private final String message;

  DeliveryErrorCode(String message) {
    this.message = message;
  }

  public String message() {
    return message;
  }
}
