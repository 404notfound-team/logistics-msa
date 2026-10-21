package com.notfound.logistics.hub.presentation.dto.stock;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Schema(description = "재고 추가 요청")
public record StockAddRequest(
    @Schema(description = "추가할 재고 수량", minimum = "1")
        @JsonDeserialize(using = StockQuantityDeserializer.class)
        @NotNull
        @Min(1)
        Long quantity) {}
