package com.notfound.logistics.delivery.domain.model;

/**
 * 허브 간 이동 구간 하나의 상태. 배송 전체 상태(DeliveryStatus)와 별개로, 구간이 끝나면 이후 배송 단계(업체 이동 등)와 관계없이 ARRIVED를 유지한다.
 */
public enum DeliveryRouteStatus {
  WAITING("이동 대기"),
  IN_TRANSIT("이동중"),
  ARRIVED("도착"),
  CANCELLED("취소");

  private final String description;

  DeliveryRouteStatus(String description) {
    this.description = description;
  }

  public String description() {
    return description;
  }
}
