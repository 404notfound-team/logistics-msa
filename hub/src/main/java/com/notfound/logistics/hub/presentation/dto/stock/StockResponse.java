package com.notfound.logistics.hub.presentation.dto.stock;

import com.notfound.logistics.hub.application.dto.stock.StockResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "허브별 상품 재고")
public record StockResponse(
    @Schema(description = "재고 ID") UUID id,
    @Schema(description = "허브 ID") UUID hubId,
    @Schema(description = "상품 ID") UUID productId,
    @Schema(description = "현재 재고 수량", minimum = "0") long quantity) {

  public static StockResponse from(StockResult result) {
    return new StockResponse(result.id(), result.hubId(), result.productId(), result.quantity());
  }
}
