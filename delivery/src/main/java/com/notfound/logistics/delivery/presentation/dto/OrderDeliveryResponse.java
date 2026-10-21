package com.notfound.logistics.delivery.presentation.dto;

import com.notfound.logistics.delivery.application.dto.DeliveryResult;
import com.notfound.logistics.delivery.domain.model.DeliveryStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** 주문별 배송 조회 결과와 알림에 필요한 현재 담당자 식별자입니다. */
@Schema(description = "주문의 배송")
public record OrderDeliveryResponse(
    @Schema(description = "배송 ID") UUID deliveryId,
    @Schema(description = "주문 ID") UUID orderId,
    @Schema(description = "배송 상태") DeliveryStatus status,
    @Schema(description = "배송 담당자 ID, 미배정 시 null") UUID assigneeId) {

  public static OrderDeliveryResponse from(DeliveryResult result) {
    return new OrderDeliveryResponse(
        result.deliveryId(), result.orderId(), result.status(), result.assigneeId());
  }
}
