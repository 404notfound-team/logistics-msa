package com.notfound.logistics.orders.presentation.dto;

import com.notfound.logistics.orders.application.dto.OrderResult;
import com.notfound.logistics.orders.domain.model.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigInteger;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "주문 당시 상품·배송 정보")
public record OrderDetailResponse(
    UUID orderId,
    UUID deliveryId,
    OrderStatus status,
    UUID receiverCompanyId,
    UUID supplierCompanyId,
    UUID productId,
    String productName,
    @Schema(description = "주문 당시 원 단위 정수 가격") long unitPrice,
    long quantity,
    @Schema(description = "단가 × 수량. 64비트 범위를 넘어도 정확한 정수") BigInteger totalPrice,
    UUID departureHubId,
    UUID destinationHubId,
    String deliveryAddress,
    String recipientName,
    String requestNotes,
    Instant createdAt,
    Instant updatedAt) {
  public static OrderDetailResponse from(OrderResult value) {
    return new OrderDetailResponse(
        value.orderId(),
        value.deliveryId(),
        value.status(),
        value.receiverCompanyId(),
        value.supplierCompanyId(),
        value.productId(),
        value.productName(),
        value.unitPrice(),
        value.quantity(),
        value.totalPrice(),
        value.departureHubId(),
        value.destinationHubId(),
        value.deliveryAddress(),
        value.recipientName(),
        value.requestNotes(),
        value.createdAt(),
        value.updatedAt());
  }
}
