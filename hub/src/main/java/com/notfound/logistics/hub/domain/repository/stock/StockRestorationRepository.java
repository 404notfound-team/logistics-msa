package com.notfound.logistics.hub.domain.repository.stock;

import com.notfound.logistics.hub.domain.model.stock.StockRestoration;
import java.util.Optional;
import java.util.UUID;

public interface StockRestorationRepository {
  // 같은 주문의 차감·복원에서 공통으로 사용하는 트랜잭션 잠금이다.
  void lockOrder(UUID orderId);

  Optional<StockRestoration> findById(UUID orderId);

  StockRestoration saveAndFlush(StockRestoration receipt);
}
