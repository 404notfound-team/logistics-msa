package com.notfound.logistics.hub.application.dto.stock;

import com.notfound.logistics.hub.domain.model.stock.Stock;
import java.util.UUID;

public record StockResult(UUID id, UUID hubId, UUID productId, long quantity) {

  public static StockResult from(Stock stock) {
    return new StockResult(
        stock.getId(), stock.getHubId(), stock.getProductId(), stock.getQuantity());
  }
}
