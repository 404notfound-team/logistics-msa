package com.notfound.logistics.delivery.presentation.dto;

import com.notfound.logistics.delivery.application.dto.DeliveryDetailResult;
import com.notfound.logistics.delivery.domain.model.DeliveryStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "배송 상세")
public record DeliveryDetailResponse(
    @Schema(description = "배송 ID") UUID deliveryId,
    @Schema(description = "주문 ID") UUID orderId,
    @Schema(description = "배송 상태") DeliveryStatus status,
    @Schema(description = "출발 허브 ID") UUID departureHubId,
    @Schema(description = "목적지 허브 ID") UUID destinationHubId,
    @Schema(description = "배송지 주소") String address,
    @Schema(description = "수령인") String recipientName,
    @Schema(description = "업체 배송 담당 사용자 ID. 배정 전에는 null", nullable = true) UUID deliveryManagerId,
    @Schema(description = "이동 구간(sequence 오름차순)") List<DeliveryRouteResponse> routes,
    @Schema(description = "생성 시각(UTC)") Instant createdAt,
    @Schema(description = "수정 시각(UTC)") Instant updatedAt) {
  public static DeliveryDetailResponse from(DeliveryDetailResult result) {
    return new DeliveryDetailResponse(
        result.deliveryId(),
        result.orderId(),
        result.status(),
        result.departureHubId(),
        result.destinationHubId(),
        result.address(),
        result.recipientName(),
        result.deliveryManagerId(),
        result.routes().stream().map(DeliveryRouteResponse::from).toList(),
        result.createdAt(),
        result.updatedAt());
  }
}
