package com.notfound.logistics.delivery.presentation.dto;

import com.notfound.logistics.delivery.application.dto.DeliveryRouteResult;
import com.notfound.logistics.delivery.domain.model.DeliveryStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "배송 이동 구간")
public record DeliveryRouteResponse(
    @Schema(description = "배송 경로 ID") UUID deliveryRouteId,
    @Schema(description = "이동 순서(1부터)") int sequence,
    @Schema(description = "출발 허브 ID") UUID departureHubId,
    @Schema(description = "도착 허브 ID") UUID arrivalHubId,
    @Schema(description = "예상 거리(km)") int expectedDistanceKm,
    @Schema(description = "실제 거리(km). 도착 전에는 null", nullable = true) Integer actualDistanceKm,
    @Schema(description = "예상 소요 시간(초)") int expectedDurationSeconds,
    @Schema(description = "실제 소요 시간(초). 도착 전에는 null", nullable = true)
        Integer actualDurationSeconds,
    @Schema(description = "구간 상태") DeliveryStatus status,
    @Schema(description = "배송 담당자 ID. 배정 전에는 null", nullable = true) UUID assigneeId) {
  public static DeliveryRouteResponse from(DeliveryRouteResult result) {
    return new DeliveryRouteResponse(
        result.deliveryRouteId(),
        result.sequence(),
        result.departureHubId(),
        result.arrivalHubId(),
        result.expectedDistanceKm(),
        result.actualDistanceKm(),
        result.expectedDurationSeconds(),
        result.actualDurationSeconds(),
        result.status(),
        result.assigneeId());
  }
}
