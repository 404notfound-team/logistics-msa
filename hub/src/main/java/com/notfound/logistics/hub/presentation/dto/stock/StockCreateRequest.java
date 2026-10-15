package com.notfound.logistics.hub.presentation.dto.stock;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

@Schema(description = "허브 재고 최초 등록 요청")
public record StockCreateRequest(
    @Schema(description = "허브 ID") @NotNull UUID hubId,
    @Schema(description = "상품 ID") @NotNull UUID productId,
    @Schema(description = "초기 재고 수량. 문자열·소수·지수 표기 없이 JSON 정수로 전달", minimum = "0")
        @JsonDeserialize(using = StockQuantityDeserializer.class)
        @NotNull
        @Min(0)
        Long quantity) {}
