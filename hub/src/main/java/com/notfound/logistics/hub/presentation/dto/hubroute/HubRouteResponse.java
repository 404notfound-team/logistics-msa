package com.notfound.logistics.hub.presentation.dto.hubroute;

import com.notfound.logistics.hub.application.dto.hubroute.HubRouteResult;
import java.math.BigDecimal;
import java.util.UUID;

public record HubRouteResponse(
    UUID id,
    UUID departureHubId,
    UUID arrivalHubId,
    Integer durationMinutes,
    BigDecimal distanceKm) {

  public static HubRouteResponse from(HubRouteResult result) {
    return new HubRouteResponse(
        result.id(),
        result.departureHubId(),
        result.arrivalHubId(),
        result.durationMinutes(),
        result.distanceKm());
  }
}
