package com.notfound.logistics.delivery.application.dto;

import java.util.Objects;
import java.util.UUID;

/** 배송을 조회·변경하는 요청자. 검증된 JWT의 사용자 ID·역할·배송 담당 여부로 구성한다. */
public record DeliveryActor(UUID userId, boolean master, boolean deliveryStaff) {
  public DeliveryActor {
    Objects.requireNonNull(userId, "userId");
  }

  public boolean assignedDeliveriesOnly() {
    return !master && deliveryStaff;
  }
}
