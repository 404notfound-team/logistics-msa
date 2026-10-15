package com.notfound.logistics.hub.application.dto.hubroute;

import com.notfound.logistics.hub.domain.model.hubroute.HubRoute;
import java.math.BigDecimal;
import java.util.UUID;

public record HubRouteResult(
    UUID id,
    UUID departureHubId,
    UUID arrivalHubId,
    Integer durationMinutes,
    BigDecimal distanceKm) {

  public static HubRouteResult from(HubRoute route) {
    return new HubRouteResult(
        route.getId(),
        route.getDepartureHubId(),
        route.getArrivalHubId(),
        route.getDurationMinutes(),
        route.getDistanceKm());
  }
}
