package com.notfound.logistics.hub.application.service.stock;

import com.notfound.logistics.hub.application.dto.stock.StockResult;
import com.notfound.logistics.hub.domain.exception.hub.HubBusinessException;
import com.notfound.logistics.hub.domain.exception.hub.HubErrorCode;
import com.notfound.logistics.hub.domain.exception.stock.StockBusinessException;
import com.notfound.logistics.hub.domain.exception.stock.StockErrorCode;
import com.notfound.logistics.hub.domain.model.stock.Stock;
import com.notfound.logistics.hub.domain.model.stock.StockRestoration;
import com.notfound.logistics.hub.domain.repository.hub.HubRepository;
import com.notfound.logistics.hub.domain.repository.stock.StockRepository;
import com.notfound.logistics.hub.domain.repository.stock.StockRestorationRepository;
import com.notfound.logistics.hub.infrastructure.client.CompanyProductClient;
import com.notfound.logistics.hub.infrastructure.client.ProductLookupException;
import feign.FeignException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StockCommandService {

  private final StockRepository stockRepository;
  private final HubRepository hubRepository;
  private final StockRestorationRepository restorations;
  private final CompanyProductClient products;

  @Transactional
  @PreAuthorize("hasRole('MASTER')")
  public StockResult create(UUID hubId, UUID productId, long quantity) {

    // 허브 존재 여부 검증
    hubRepository
        .findById(hubId)
        .orElseThrow(() -> new HubBusinessException(HubErrorCode.HUB_NOT_FOUND));

    if (stockRepository.existsByHubIdAndProductId(hubId, productId)) {
      throw new StockBusinessException(StockErrorCode.STOCK_ALREADY_EXISTS);
    }

    verifyProduct(productId);

    Stock stock = Stock.create(hubId, productId, quantity);

    return StockResult.from(stockRepository.saveAndFlush(stock));
  }

  private void verifyProduct(UUID productId) {
    try {
      var response = products.getProduct(productId);
      if (response == null || response.data() == null || !productId.equals(response.data().id())) {
        throw new ProductLookupException();
      }
    } catch (FeignException exception) {
      throw new ProductLookupException(exception);
    }
  }

  @Transactional
  @PreAuthorize("hasRole('MASTER')")
  public StockResult add(UUID hubId, UUID productId, long quantity) {

    Stock stock =
        stockRepository
            .findLockedByHubIdAndProductId(hubId, productId)
            .orElseThrow(() -> new StockBusinessException(StockErrorCode.STOCK_NOT_FOUND));

    stock.add(quantity);

    return StockResult.from(stock);
  }

  @Transactional
  @PreAuthorize("hasAuthority('SERVICE_ORDERS')")
  public StockResult deduct(UUID hubId, UUID productId, long quantity) {
    Stock stock =
        stockRepository
            .findLockedByHubIdAndProductId(hubId, productId)
            .orElseThrow(() -> new StockBusinessException(StockErrorCode.STOCK_NOT_FOUND));

    stock.deduct(quantity);

    return StockResult.from(stock);
  }

  @Transactional
  @PreAuthorize("hasAuthority('SERVICE_ORDERS')")
  public StockResult restore(UUID orderId, UUID hubId, UUID productId, long quantity) {
    restorations.lockOrder(orderId);
    var previous = restorations.findById(orderId);
    if (previous.isPresent()) {
      var receipt = previous.get();
      receipt.requireSameRequest(hubId, productId, quantity);
      return new StockResult(
          receipt.getStockId(),
          receipt.getHubId(),
          receipt.getProductId(),
          receipt.getRemainingQuantity());
    }
    Stock stock =
        stockRepository
            .findLockedByHubIdAndProductId(hubId, productId)
            .orElseThrow(() -> new StockBusinessException(StockErrorCode.STOCK_NOT_FOUND));

    stock.restore(quantity);
    restorations.saveAndFlush(StockRestoration.completed(orderId, stock, quantity));

    return StockResult.from(stock);
  }
}
