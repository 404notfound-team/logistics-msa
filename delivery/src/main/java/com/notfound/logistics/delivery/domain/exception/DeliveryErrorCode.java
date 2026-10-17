package com.notfound.logistics.delivery.domain.exception;

public enum DeliveryErrorCode {
  DELIVERY_NOT_FOUND("배송을 찾을 수 없습니다."),
  ASSIGNMENT_NOT_ALLOWED("허브 대기중인 배송만 담당자를 배정할 수 있습니다."),
  STATUS_CHANGE_FORBIDDEN("MASTER 또는 해당 배송의 담당자만 배송 상태를 변경할 수 있습니다."),
  ASSIGNMENT_FORBIDDEN("MASTER만 배송 담당자를 배정할 수 있습니다."),
  INVALID_STATUS_TRANSITION("현재 배송 상태에서 요청한 상태로 변경할 수 없습니다."),
  CONCURRENT_MODIFICATION("다른 요청이 배송을 먼저 변경했습니다. 다시 조회한 뒤 요청해 주세요."),
  // 주문에 이미 다른 내용(허브·주소·수령인)의 배송이 있다.
  DELIVERY_ALREADY_EXISTS("주문에 이미 배송이 있습니다."),
  // 출발 또는 목적지 허브가 Hub에 없다.
  HUB_NOT_FOUND("허브를 찾을 수 없습니다."),
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
