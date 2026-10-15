package com.notfound.logistics.orders.domain.exception;

public enum OrderErrorCode {
  ORDER_NOT_FOUND("주문을 찾을 수 없습니다."),
  ORDER_STATE_CONFLICT("현재 주문 상태에서는 요청을 처리할 수 없습니다.");

  private final String message;

  OrderErrorCode(String message) {
    this.message = message;
  }

  public String message() {
    return message;
  }
}
