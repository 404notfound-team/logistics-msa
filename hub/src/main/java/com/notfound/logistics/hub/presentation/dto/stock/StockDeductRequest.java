package com.notfound.logistics.hub.presentation.dto.stock;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

@Schema(description = "내부 재고 차감 요청")
public record StockDeductRequest(
    @Schema(description = "주문 ID") @NotNull UUID orderId,
    @Schema(description = "허브 ID") @NotNull UUID hubId,
    @Schema(description = "상품 ID") @NotNull UUID productId,
    @Schema(description = "차감할 수량", minimum = "1")
        @JsonDeserialize(using = StockQuantityDeserializer.class)
        @NotNull
        @Min(1)
        Long quantity) {}
