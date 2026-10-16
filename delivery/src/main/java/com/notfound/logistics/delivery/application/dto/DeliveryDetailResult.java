package com.notfound.logistics.delivery.application.dto;

import com.notfound.logistics.delivery.domain.model.Delivery;
import com.notfound.logistics.delivery.domain.model.DeliveryRoute;
import com.notfound.logistics.delivery.domain.model.DeliveryStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 배송 상세 조회 결과. 목록 항목(DeliveryResult)에 이동 구간을 더한다. */
public record DeliveryDetailResult(
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
  public static DeliveryDetailResult of(Delivery delivery, List<DeliveryRoute> routes) {
    return new DeliveryDetailResult(
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
