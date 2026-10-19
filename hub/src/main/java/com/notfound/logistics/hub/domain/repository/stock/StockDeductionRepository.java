package com.notfound.logistics.hub.domain.repository.stock;

import com.notfound.logistics.hub.domain.model.stock.StockDeduction;
import java.util.Optional;
import java.util.UUID;

public interface StockDeductionRepository {
  Optional<StockDeduction> findById(UUID orderId);

  StockDeduction saveAndFlush(StockDeduction result);
}
