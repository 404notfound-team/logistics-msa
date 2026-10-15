package com.notfound.logistics.orders.global.exception;

import com.notfound.logistics.common.api.ApiErrorCode;

public enum OrderErrorCode implements ApiErrorCode {
  ORDER_NOT_FOUND(404, "주문을 찾을 수 없습니다."),
  ORDER_STATE_CONFLICT(409, "현재 주문 상태에서는 요청을 처리할 수 없습니다.");

  private final int httpStatus;
  private final String message;

  OrderErrorCode(int httpStatus, String message) {
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
