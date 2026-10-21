package com.notfound.logistics.orders.application.dto;

import java.util.UUID;

public record CreateOrderCommand(
    UUID receiverCompanyId,
    UUID supplierCompanyId,
    UUID productId,
    long quantity,
    String deliveryAddress,
    String recipientName,
    String requestNotes,
    UUID idempotencyKey) {
  public CreateOrderCommand {
    deliveryAddress = deliveryAddress == null ? null : deliveryAddress.strip();
    recipientName = recipientName == null ? null : recipientName.strip();
    requestNotes = requestNotes == null || requestNotes.isBlank() ? null : requestNotes.strip();
  }
}
