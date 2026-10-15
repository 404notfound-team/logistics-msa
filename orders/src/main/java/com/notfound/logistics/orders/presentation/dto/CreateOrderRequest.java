package com.notfound.logistics.orders.presentation.dto;

import com.notfound.logistics.orders.application.dto.CreateOrderCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;

@Schema(description = "주문 생성 요청")
public record CreateOrderRequest(
    @NotNull UUID receiverCompanyId,
    @NotNull UUID supplierCompanyId,
    @NotNull UUID productId,
    @NotNull @Positive Long quantity,
    @NotBlank @Size(max = 255) String deliveryAddress,
    @NotBlank @Size(max = 100) String recipientName,
    String requestNotes) {

  public CreateOrderCommand toCommand(UUID idempotencyKey) {
    return new CreateOrderCommand(
        receiverCompanyId,
        supplierCompanyId,
        productId,
        quantity,
        deliveryAddress,
        recipientName,
        requestNotes,
        idempotencyKey);
  }
}
