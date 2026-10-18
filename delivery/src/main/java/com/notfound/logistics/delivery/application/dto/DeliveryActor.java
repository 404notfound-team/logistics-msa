package com.notfound.logistics.delivery.application.dto;

import java.util.Objects;
import java.util.UUID;

/** 배송을 변경하는 요청자. presentation이 인증 정보로 만들어 application의 권한 검사에 넘긴다. */
public record DeliveryActor(UUID userId, boolean master) {
  public DeliveryActor {
    Objects.requireNonNull(userId, "userId");
  }
}
