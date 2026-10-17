package com.notfound.logistics.delivery.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Orders 서비스가 주문 생성 후 보내는 배송 생성 요청. 본문은 snake_case로 받는다. */
@Schema(description = "배송 생성 요청")
public record DeliveryCreateRequest(
    @Schema(description = "주문 ID") @NotNull UUID orderId,
    @Schema(description = "출발 허브 ID") @NotNull UUID departureHubId,
    @Schema(description = "목적지 허브 ID") @NotNull UUID destinationHubId,
    @Schema(description = "배송지 주소") @NotBlank @Size(max = 255) String address,
    @Schema(description = "수령인") @NotBlank @Size(max = 100) String recipientName) {}
