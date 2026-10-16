package com.notfound.logistics.delivery.application.dto;

import com.notfound.logistics.delivery.domain.model.Delivery;
import com.notfound.logistics.delivery.domain.model.DeliveryStatus;
import java.time.Instant;
import java.util.UUID;

/** presentation -> application 의존 방향 유지를 위해 사용 */
public record DeliveryResult(
    UUID deliveryId,
    UUID orderId,
    DeliveryStatus status,
    UUID departureHubId,
    UUID destinationHubId,
    String address,
    String recipientName,
    UUID deliveryManagerId,
    Instant createdAt,
    Instant updatedAt) {
  public static DeliveryResult from(Delivery delivery) {
    return new DeliveryResult(
        delivery.getId(),
        delivery.getOrderId(),
        delivery.getStatus(),
        delivery.getDepartureHubId(),
        delivery.getDestinationHubId(),
        delivery.getAddress(),
        delivery.getRecipientName(),
        delivery.getDeliveryManagerId(),
        delivery.getCreatedAt(),
        delivery.getUpdatedAt());
  }
}
