package com.notfound.logistics.hub.application.service.stock;

import com.notfound.logistics.hub.application.dto.stock.StockResult;
import com.notfound.logistics.hub.domain.exception.hub.HubBusinessException;
import com.notfound.logistics.hub.domain.exception.hub.HubErrorCode;
import com.notfound.logistics.hub.domain.exception.stock.StockBusinessException;
import com.notfound.logistics.hub.domain.exception.stock.StockErrorCode;
import com.notfound.logistics.hub.domain.model.stock.Stock;
import com.notfound.logistics.hub.domain.repository.hub.HubRepository;
import com.notfound.logistics.hub.domain.repository.stock.StockRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StockCommandService {

  private final StockRepository stockRepository;
  private final HubRepository hubRepository;

  @Transactional
  public StockResult create(UUID hubId, UUID productId, long quantity) {

    // TODO(Users/Gateway): 인증·권한 계약 확정 후 검증된 호출자의 재고 등록 권한을 확인한다.

    // 허브 존재 여부 검증
    hubRepository
        .findById(hubId)
        .orElseThrow(() -> new HubBusinessException(HubErrorCode.HUB_NOT_FOUND));

    // TODO(Company): 상품 내부 조회 API 구현 후 외부 연동 인터페이스로 존재·논리 삭제 여부를 확인한다.
    // 상품 없음과 연동 실패를 구분하고, 공급업체·관리 허브 관계 검증 책임은 담당자와 확정한다.

    if (stockRepository.existsByHubIdAndProductId(hubId, productId)) {
      throw new StockBusinessException(StockErrorCode.STOCK_ALREADY_EXISTS);
    }

    Stock stock = Stock.create(hubId, productId, quantity);

    return StockResult.from(stockRepository.saveAndFlush(stock));
  }
}
