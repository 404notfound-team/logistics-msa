package com.notfound.logistics.delivery.domain.model;

/**
 * 허브 간 이동(HUB_*)을 거쳐 목적지 허브에서 업체로 배송(COMPANY_MOVING)되는 흐름이다.
 *
 * <p>바로 다음 배송 상태로만 진행한다. 취소는 출발 전 주문 취소 API에서만 처리하며 완료·취소는 종료 상태다.
 */
public enum DeliveryStatus {
  HUB_WAITING("허브 대기중"),
  HUB_MOVING("허브 이동중"),
  DESTINATION_HUB_ARRIVED("목적지 허브 도착"),
  COMPANY_MOVING("업체 이동중"),
  DELIVERED("배송 완료"),
  CANCELLED("배송 취소");

  private final String description;

  DeliveryStatus(String description) {
    this.description = description;
  }

  public String description() {
    return description;
  }

  /** 현재 상태에서 next로 바로 바꿀 수 있는지. 바로 다음 단계만 허용한다. */
  public boolean canTransitionTo(DeliveryStatus next) {
    return switch (this) {
      case HUB_WAITING -> next == HUB_MOVING;
      case HUB_MOVING -> next == DESTINATION_HUB_ARRIVED;
      case DESTINATION_HUB_ARRIVED -> next == COMPANY_MOVING;
      case COMPANY_MOVING -> next == DELIVERED;
      case DELIVERED, CANCELLED -> false;
    };
  }
}
