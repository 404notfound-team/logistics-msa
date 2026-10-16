package com.notfound.logistics.delivery.domain.exception;

public enum DeliveryErrorCode {
  DELIVERY_NOT_FOUND("배송을 찾을 수 없습니다."),
  ASSIGNMENT_NOT_ALLOWED("허브 대기중인 배송만 담당자를 배정할 수 있습니다.");

  private final String message;

  DeliveryErrorCode(String message) {
    this.message = message;
  }

  public String message() {
    return message;
  }
}
