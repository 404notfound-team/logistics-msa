package com.notfound.logistics.delivery.domain.model;

/**
 * 허브 간 이동(HUB_*)을 거쳐 목적지 허브에서 업체로 배송(COMPANY_TRANSIT)되는 흐름이다. 출발 전(WAITING)에만 취소(CANCELLED)할 수 있다.
 */
public enum DeliveryStatus {
  WAITING("허브 대기중"),
  HUB_TRANSIT("허브 이동중"),
  DESTINATION_HUB_ARRIVED("목적지 허브 도착"),
  COMPANY_TRANSIT("업체 이동중"),
  COMPLETED("배송 완료"),
  CANCELLED("배송 취소");

  private final String description;

  DeliveryStatus(String description) {
    this.description = description;
  }

  public String description() {
    return description;
  }
}
