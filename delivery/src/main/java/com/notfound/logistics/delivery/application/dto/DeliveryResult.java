package com.notfound.logistics.delivery.application.dto;

import com.notfound.logistics.delivery.domain.model.Delivery;
import com.notfound.logistics.delivery.domain.model.DeliveryRoute;
import com.notfound.logistics.delivery.domain.model.DeliveryStatus;
import java.time.Instant;
import java.util.List;
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
    List<DeliveryRouteResult> routes,
    Instant createdAt,
    Instant updatedAt) {
  public static DeliveryResult of(Delivery delivery, List<DeliveryRoute> routes) {
    return new DeliveryResult(
        delivery.getId(),
        delivery.getOrderId(),
        delivery.getStatus(),
        delivery.getDepartureHubId(),
        delivery.getDestinationHubId(),
        delivery.getAddress(),
        delivery.getRecipientName(),
        delivery.getDeliveryManagerId(),
        routes.stream().map(DeliveryRouteResult::from).toList(),
        delivery.getCreatedAt(),
        delivery.getUpdatedAt());
  }
}
