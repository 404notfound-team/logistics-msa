package com.notfound.logistics.hub.application.service.stock;

import com.notfound.logistics.hub.application.dto.stock.StockResult;
import com.notfound.logistics.hub.domain.exception.stock.StockBusinessException;
import com.notfound.logistics.hub.domain.exception.stock.StockErrorCode;
import com.notfound.logistics.hub.domain.model.stock.StockDeduction;
import com.notfound.logistics.hub.domain.model.stock.StockRestoration;
import com.notfound.logistics.hub.domain.repository.stock.StockDeductionRepository;
import com.notfound.logistics.hub.domain.repository.stock.StockRepository;
import com.notfound.logistics.hub.domain.repository.stock.StockRestorationRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 재고 수량 변경과 주문별 처리 기록을 하나의 로컬 트랜잭션으로 저장한다. */
@Service
@RequiredArgsConstructor
public class StockMovementTransactions {
  private final StockRepository stocks;
  private final StockDeductionRepository deductions;
  private final StockRestorationRepository restorations;

  @Transactional
  public StockDeduction deduct(UUID orderId, UUID hubId, UUID productId, long quantity) {
    validate(quantity);
    // 차감과 복원이 같은 주문 잠금을 공유해 동시 처리를 막는다.
    restorations.lockOrder(orderId);
    var previous = deductions.findById(orderId);
    previous.ifPresent(result -> result.requireSameRequest(hubId, productId, quantity));
    var restored = restorations.findById(orderId);
    if (restored.isPresent()) {
      restored.get().requireSameRequest(hubId, productId, quantity);
      throw new StockBusinessException(StockErrorCode.STOCK_ALREADY_RESTORED);
    }
    if (previous.isPresent()) return previous.get();
    var stock = stocks.findLockedByHubIdAndProductId(hubId, productId);
    // 확정 거절도 저장해 이후 입고 여부와 관계없이 최초 결과를 유지한다.
    if (stock.isEmpty()) {
      return deductions.saveAndFlush(
          StockDeduction.rejected(
              orderId, hubId, productId, quantity, StockErrorCode.STOCK_NOT_FOUND));
    }
    try {
      stock.get().deduct(quantity);
    } catch (StockBusinessException exception) {
      if (exception.getErrorCode() != StockErrorCode.INSUFFICIENT_STOCK) throw exception;
      return deductions.saveAndFlush(
          StockDeduction.rejected(orderId, hubId, productId, quantity, exception.getErrorCode()));
    }
    return deductions.saveAndFlush(StockDeduction.completed(orderId, stock.get(), quantity));
  }

  @Transactional
  public StockResult restore(UUID orderId, UUID hubId, UUID productId, long quantity) {
    validate(quantity);
    restorations.lockOrder(orderId);
    var previous = restorations.findById(orderId);

    // 기존 복원 결과를 먼저 재현해 과거 차감 기록이 없는 완료 건도 유지한다.
    if (previous.isPresent()) {
      var receipt = previous.get();
      receipt.requireSameRequest(hubId, productId, quantity);
      return new StockResult(
          receipt.getStockId(),
          receipt.getHubId(),
          receipt.getProductId(),
          receipt.getRemainingQuantity());
    }
    // 최초 복원은 실제 차감 성공 기록과 요청 내용이 일치해야 한다.
    var deduction =
        deductions
            .findById(orderId)
            .orElseThrow(
                () -> new StockBusinessException(StockErrorCode.STOCK_DEDUCTION_NOT_FOUND));
    deduction.requireRestorable(hubId, productId, quantity);
    var stock =
        stocks
            .findLockedByHubIdAndProductId(deduction.getHubId(), deduction.getProductId())
            .orElseThrow(() -> new StockBusinessException(StockErrorCode.STOCK_NOT_FOUND));
    if (!stock.getId().equals(deduction.getStockId()))
      throw new StockBusinessException(StockErrorCode.STOCK_RESTORATION_CONFLICT);
    // 요청값이 아닌 기록된 원래 차감 수량을 복원한다.
    stock.restore(deduction.getQuantity());
    restorations.saveAndFlush(StockRestoration.completed(orderId, stock, deduction.getQuantity()));
    return StockResult.from(stock);
  }

  private static void validate(long quantity) {
    if (quantity <= 0) throw new StockBusinessException(StockErrorCode.INVALID_STOCK_QUANTITY);
  }
}
