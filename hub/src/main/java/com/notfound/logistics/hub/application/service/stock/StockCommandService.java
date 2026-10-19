package com.notfound.logistics.hub.application.service.stock;

import com.notfound.logistics.hub.application.dto.stock.StockResult;
import com.notfound.logistics.hub.domain.exception.hub.HubBusinessException;
import com.notfound.logistics.hub.domain.exception.hub.HubErrorCode;
import com.notfound.logistics.hub.domain.exception.stock.StockBusinessException;
import com.notfound.logistics.hub.domain.exception.stock.StockErrorCode;
import com.notfound.logistics.hub.domain.model.stock.Stock;
import com.notfound.logistics.hub.domain.repository.hub.HubRepository;
import com.notfound.logistics.hub.domain.repository.stock.StockRepository;
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
  private final CompanyProductClient products;
  private final StockMovementTransactions movements;

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

  @PreAuthorize("hasAuthority('SERVICE_ORDERS')")
  public StockResult deduct(UUID orderId, UUID hubId, UUID productId, long quantity) {
    // 확정 거절 기록이 롤백되지 않도록 트랜잭션 커밋 후 예외로 변환한다.
    var result = movements.deduct(orderId, hubId, productId, quantity);
    if (result.getFailureCode() != null) throw new StockBusinessException(result.getFailureCode());
    return new StockResult(
        result.getStockId(),
        result.getHubId(),
        result.getProductId(),
        result.getRemainingQuantity());
  }

  @PreAuthorize("hasAuthority('SERVICE_ORDERS')")
  public StockResult restore(UUID orderId, UUID hubId, UUID productId, long quantity) {
    return movements.restore(orderId, hubId, productId, quantity);
  }
}
