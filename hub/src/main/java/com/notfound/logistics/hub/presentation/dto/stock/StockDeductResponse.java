package com.notfound.logistics.hub.presentation.dto.stock;

import com.notfound.logistics.hub.application.dto.stock.StockResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "내부 재고 차감 응답")
public record StockDeductResponse(UUID orderId, UUID stockId, long quantity, String state) {

  public static StockDeductResponse from(UUID orderId, long deductedQuantity, StockResult result) {
    return new StockDeductResponse(orderId, result.id(), deductedQuantity, "DEDUCTED");
  }
}
