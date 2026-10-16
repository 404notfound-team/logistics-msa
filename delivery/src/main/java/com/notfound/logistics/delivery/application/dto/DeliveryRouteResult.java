package com.notfound.logistics.delivery.application.dto;

import com.notfound.logistics.delivery.domain.model.DeliveryRoute;
import com.notfound.logistics.delivery.domain.model.DeliveryStatus;
import java.math.BigDecimal;
import java.util.UUID;

public record DeliveryRouteResult(
    UUID deliveryRouteId,
    int sequence,
    UUID departureHubId,
    UUID arrivalHubId,
    BigDecimal expectedDistanceKm,
    BigDecimal actualDistanceKm,
    long expectedDurationSeconds,
    Long actualDurationSeconds,
    DeliveryStatus status,
    UUID assigneeId) {
  public static DeliveryRouteResult from(DeliveryRoute route) {
    return new DeliveryRouteResult(
        route.getId(),
        route.getSequence(),
        route.getDepartureHubId(),
        route.getArrivalHubId(),
        route.getExpectedDistanceKm(),
        route.getActualDistanceKm(),
        route.getExpectedDurationSeconds(),
        route.getActualDurationSeconds(),
        route.getStatus(),
        route.getAssigneeId());
  }
}
