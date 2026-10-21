package com.notfound.logistics.orders.application.dto;

import com.notfound.logistics.orders.domain.model.Order;
import com.notfound.logistics.orders.domain.model.OrderStatus;
import java.math.BigInteger;
import java.time.Instant;
import java.util.UUID;

public record OrderResult(
    UUID orderId,
    UUID deliveryId,
    OrderStatus status,
    UUID receiverCompanyId,
    UUID supplierCompanyId,
    UUID productId,
    String productName,
    long unitPrice,
    long quantity,
    BigInteger totalPrice,
    UUID departureHubId,
    UUID destinationHubId,
    String deliveryAddress,
    String recipientName,
    String requestNotes,
    Instant createdAt,
    Instant updatedAt) {
  public static OrderResult from(Order order) {
    return new OrderResult(
        order.getId(),
        order.getDeliveryId(),
        order.getStatus(),
        order.getReceiverCompanyId(),
        order.getSupplierCompanyId(),
        order.getProductId(),
        order.getProductName(),
        order.getUnitPrice(),
        order.getQuantity(),
        order.totalPrice(),
        order.getDepartureHubId(),
        order.getDestinationHubId(),
        order.getDeliveryAddress(),
        order.getRecipientName(),
        order.getRequestNotes(),
        order.getCreatedAt(),
        order.getUpdatedAt());
  }
}
