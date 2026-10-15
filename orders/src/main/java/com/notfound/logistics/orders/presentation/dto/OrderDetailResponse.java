package com.notfound.logistics.orders.presentation.dto;

import com.notfound.logistics.orders.application.dto.OrderResult;
import com.notfound.logistics.orders.domain.model.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
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
    BigDecimal unitPrice,
    long quantity,
    BigDecimal totalPrice,
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
