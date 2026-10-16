package com.notfound.logistics.hub.domain.repository.stock;

import com.notfound.logistics.hub.domain.model.stock.Stock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface StockRepository {

  boolean existsByHubIdAndProductId(UUID hubId, UUID productId);

  Stock saveAndFlush(Stock stock);

  Optional<Stock> findByHubIdAndProductId(UUID hubId, UUID productId);

  Optional<Stock> findById(UUID id);

  Page<Stock> findByHubIdAndProductId(UUID hubId, UUID productId, Pageable pageable);

  Page<Stock> findByHubId(UUID hubId, Pageable pageable);

  Page<Stock> findByProductId(UUID productId, Pageable pageable);

  Page<Stock> findAll(Pageable pageable);

  long countByHubIdAndProductId(UUID hubId, UUID productId);

  long countByHubId(UUID hubId);

  long countByProductId(UUID productId);

  long count();

  Optional<Stock> findLockedByHubIdAndProductId(UUID hubId, UUID productId);
}
