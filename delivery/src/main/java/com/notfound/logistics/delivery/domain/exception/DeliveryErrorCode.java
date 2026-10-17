package com.notfound.logistics.delivery.domain.exception;

public enum DeliveryErrorCode {
  DELIVERY_NOT_FOUND("배송을 찾을 수 없습니다."),
  // 주문에 이미 다른 내용(허브·주소·수령인)의 배송이 있다.
  DELIVERY_ALREADY_EXISTS("주문에 이미 배송이 있습니다."),
  // Hub에 출발 → 목적지 허브의 기준 경로가 없다.
  HUB_ROUTE_NOT_FOUND("허브 간 이동 경로를 찾을 수 없습니다."),
  // Hub 호출이 실패했다(연결 실패, 5xx, 인증 오류 등).
  HUB_UNAVAILABLE("허브 서비스를 이용할 수 없습니다.");

  private final String message;

  DeliveryErrorCode(String message) {
    this.message = message;
  }

  public String message() {
    return message;
  }
}
