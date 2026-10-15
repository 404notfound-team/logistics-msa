package com.notfound.logistics.hub.infrastructure.persistence.stock;

import com.notfound.logistics.hub.domain.model.stock.Stock;
import com.notfound.logistics.hub.domain.repository.stock.StockRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockJpaRepository extends JpaRepository<Stock, UUID>, StockRepository {
  // 논리 삭제된 키도 유니크 제약에 남으므로 사전 중복 검사에 포함한다.
  @Query(
      value =
          "SELECT EXISTS (SELECT 1 FROM hub.p_stocks WHERE hub_id = :hubId AND product_id = :productId)",
      nativeQuery = true)
  boolean existsByHubIdAndProductId(@Param("hubId") UUID hubId, @Param("productId") UUID productId);

  Optional<Stock> findByHubIdAndProductId(UUID hubId, UUID productId);

  Page<Stock> findByHubIdAndProductId(UUID hubId, UUID productId, Pageable pageable);

  Page<Stock> findByHubId(UUID hubId, Pageable pageable);

  Page<Stock> findByProductId(UUID productId, Pageable pageable);

  long countByHubIdAndProductId(UUID hubId, UUID productId);

  long countByHubId(UUID hubId);

  long countByProductId(UUID productId);
}
