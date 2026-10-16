package com.notfound.logistics.delivery.domain.exception;

public enum DeliveryErrorCode {
  DELIVERY_NOT_FOUND("배송을 찾을 수 없습니다.");

  private final String message;

  DeliveryErrorCode(String message) {
    this.message = message;
  }

  public String message() {
    return message;
  }
}
