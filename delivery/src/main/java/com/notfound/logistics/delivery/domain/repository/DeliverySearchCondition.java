package com.notfound.logistics.delivery.domain.repository;

import com.notfound.logistics.delivery.domain.model.DeliveryStatus;
import java.util.UUID;

/** null인 조건은 적용하지 않는다. 주소와 수령인은 대소문자 구분 없는 부분 일치로 검색한다. */
public record DeliverySearchCondition(
    UUID orderId,
    DeliveryStatus status,
    UUID departureHubId,
    UUID destinationHubId,
    String address,
    String recipientName,
    Long deliveryManagerId) {
  public DeliverySearchCondition {
    address = blankToNull(address);
    recipientName = blankToNull(recipientName);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
}
