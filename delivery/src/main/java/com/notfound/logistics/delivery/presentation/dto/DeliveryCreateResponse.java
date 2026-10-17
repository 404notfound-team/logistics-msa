package com.notfound.logistics.delivery.presentation.dto;

import com.notfound.logistics.delivery.application.dto.DeliveryResult;
import com.notfound.logistics.delivery.domain.model.DeliveryStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** 배송 생성 응답. 내부 API 명세대로 delivery_id, order_id, status만 응답한다. */
@Schema(description = "배송 생성 결과")
public record DeliveryCreateResponse(
    @Schema(description = "배송 ID") UUID deliveryId,
    @Schema(description = "주문 ID") UUID orderId,
    @Schema(description = "배송 상태") DeliveryStatus status) {

  public static DeliveryCreateResponse from(DeliveryResult result) {
    return new DeliveryCreateResponse(result.deliveryId(), result.orderId(), result.status());
  }
}
