package com.notfound.logistics.delivery.domain.model;

/** 허브 간 이동(HUB_*)을 거쳐 목적지 허브에서 업체로 배송(COMPANY_MOVING)되는 흐름이다. */
public enum DeliveryStatus {
  HUB_WAITING("허브 대기중"),
  HUB_MOVING("허브 이동중"),
  DESTINATION_HUB_ARRIVED("목적지 허브 도착"),
  COMPANY_MOVING("업체 이동중"),
  DELIVERED("배송 완료");

  private final String description;

  DeliveryStatus(String description) {
    this.description = description;
  }

  public String description() {
    return description;
  }
}
