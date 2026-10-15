package com.notfound.logistics.orders.presentation.dto;

import com.notfound.logistics.orders.application.dto.OrderResult;
import com.notfound.logistics.orders.domain.model.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "주문 처리 결과")
public record OrderResponse(
    @Schema(description = "주문 ID") UUID orderId,
    @Schema(description = "배송 ID. 생성 확인 전에는 null") UUID deliveryId,
    @Schema(description = "현재 주문 상태") OrderStatus status) {

  public static OrderResponse from(OrderResult result) {
    return new OrderResponse(result.orderId(), result.deliveryId(), result.status());
  }
}
