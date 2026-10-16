package com.notfound.logistics.hub.domain.repository.stock;

import com.notfound.logistics.hub.domain.model.stock.StockRestoration;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockRestorationRepository extends JpaRepository<StockRestoration, UUID> {
  // An order can race with a different hub/product. A stock-row lock alone cannot serialize that.
  // Hash collisions only serialize unrelated orders; the receipt's full UUID remains the unique
  // key.
  @Query(
      value = "select 1 from pg_advisory_xact_lock(hashtextextended(cast(:orderId as text), 0))",
      nativeQuery = true)
  int lockOrder(@Param("orderId") UUID orderId);
}
