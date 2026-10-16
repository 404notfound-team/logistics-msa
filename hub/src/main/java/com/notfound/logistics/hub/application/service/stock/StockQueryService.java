package com.notfound.logistics.hub.application.service.stock;

import com.notfound.logistics.hub.application.dto.stock.StockResult;
import com.notfound.logistics.hub.application.service.QuerySort;
import com.notfound.logistics.hub.domain.exception.stock.StockBusinessException;
import com.notfound.logistics.hub.domain.exception.stock.StockErrorCode;
import com.notfound.logistics.hub.domain.model.stock.Stock;
import com.notfound.logistics.hub.domain.repository.stock.StockRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StockQueryService {

  private final StockRepository stockRepository;

  public StockResult getStock(UUID hubId, UUID productId) {
    return stockRepository
        .findByHubIdAndProductId(hubId, productId)
        .map(StockResult::from)
        .orElseThrow(() -> new StockBusinessException(StockErrorCode.STOCK_NOT_FOUND));
  }

  public Page<StockResult> getStocks(UUID hubId, UUID productId, int page, int size) {
    return getStocks(hubId, productId, page, size, null);
  }

  public Page<StockResult> getStocks(UUID hubId, UUID productId, int page, int size, String sort) {

    Pageable pageable = PageRequest.of(page, size, QuerySort.resolve(sort, "desc"));

    if (pageable.getOffset() >= Integer.MAX_VALUE) {
      long total = countStocks(hubId, productId);
      return new PageImpl<>(List.of(), pageable, total);
    }

    Page<Stock> stocks;

    if (hubId != null && productId != null) {
      stocks = stockRepository.findByHubIdAndProductId(hubId, productId, pageable);
    } else if (hubId != null) {
      stocks = stockRepository.findByHubId(hubId, pageable);
    } else if (productId != null) {
      stocks = stockRepository.findByProductId(productId, pageable);
    } else {
      stocks = stockRepository.findAll(pageable);
    }

    return stocks.map(StockResult::from);
  }

  private long countStocks(UUID hubId, UUID productId) {
    if (hubId != null && productId != null) {
      return stockRepository.countByHubIdAndProductId(hubId, productId);
    }

    if (hubId != null) {
      return stockRepository.countByHubId(hubId);
    }

    if (productId != null) {
      return stockRepository.countByProductId(productId);
    }

    return stockRepository.count();
  }
}
