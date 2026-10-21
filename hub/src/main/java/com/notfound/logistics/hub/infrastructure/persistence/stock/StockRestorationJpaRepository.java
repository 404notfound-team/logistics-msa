package com.notfound.logistics.hub.infrastructure.persistence.stock;

import com.notfound.logistics.hub.domain.model.stock.StockRestoration;
import com.notfound.logistics.hub.domain.repository.stock.StockRestorationRepository;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockRestorationJpaRepository
    extends JpaRepository<StockRestoration, UUID>, StockRestorationRepository {
  @Query(value = "SELECT set_config('lock_timeout', :timeout, true)", nativeQuery = true)
  String configureLockTimeout(@Param("timeout") String timeout);

  @Query(
      value = "SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(cast(:orderId AS text), 0))",
      nativeQuery = true)
  int acquireOrderLock(@Param("orderId") UUID orderId);

  @Override
  default void lockOrder(UUID orderId) {
    configureLockTimeout("1500ms");
    acquireOrderLock(orderId);
    // 주문 잠금 대기는 짧게 제한하고, 획득 후에는 HTTP 타임아웃 뒤에도 커밋할 여유를 둔다.
    configureLockTimeout("10s");
  }
}
