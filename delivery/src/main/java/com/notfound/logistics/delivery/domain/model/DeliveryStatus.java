package com.notfound.logistics.delivery.domain.model;

/**
 * 허브 간 이동(HUB_*)을 거쳐 목적지 허브에서 업체로 배송(COMPANY_MOVING)되는 흐름이다.
 *
 * <p>선언 순서가 곧 진행 순서이며, 바로 다음 상태로만 한 단계씩 나아간다. 되돌리거나 건너뛸 수 없고 DELIVERED는 마지막 상태다.
 */
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

  /** 현재 상태에서 next로 바로 바꿀 수 있는지. 바로 다음 단계만 허용한다. */
  public boolean canTransitionTo(DeliveryStatus next) {
    return next.ordinal() == ordinal() + 1;
  }
}
