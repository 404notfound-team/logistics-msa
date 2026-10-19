package com.notfound.logistics.hub.domain.model.stock;

import com.notfound.logistics.common.audit.AuditEntity;
import com.notfound.logistics.hub.domain.exception.stock.StockBusinessException;
import com.notfound.logistics.hub.domain.exception.stock.StockErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.SQLRestriction;

/** 주문별 차감 결과를 보관하며, 성공과 확정 거절 모두 같은 주문 키로 재현한다. */
@Entity
@Table(name = "p_stock_deductions", schema = "hub")
@Check(
    constraints =
        "quantity > 0 AND ((failure_code IS NULL AND stock_id IS NOT NULL AND remaining_quantity IS NOT NULL AND remaining_quantity >= 0) OR (failure_code IS NOT NULL AND failure_code IN ('STOCK_NOT_FOUND', 'INSUFFICIENT_STOCK') AND stock_id IS NULL AND remaining_quantity IS NULL))")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockDeduction extends AuditEntity {
  @Id private UUID orderId;

  @Column(nullable = false, updatable = false)
  private UUID hubId;

  @Column(nullable = false, updatable = false)
  private UUID productId;

  @Column(nullable = false, updatable = false)
  private long quantity;

  @Column(updatable = false)
  private UUID stockId;

  // 재요청에는 현재 잔량 대신 최초 차감 직후의 잔량을 반환한다.
  @Column(updatable = false)
  private Long remainingQuantity;

  // null이면 성공이며, 재고 없음·부족만 확정 거절로 저장한다.
  @Enumerated(EnumType.STRING)
  @Column(updatable = false, length = 64)
  private StockErrorCode failureCode;

  private static StockDeduction request(UUID orderId, UUID hubId, UUID productId, long quantity) {
    if (quantity <= 0) throw new StockBusinessException(StockErrorCode.INVALID_STOCK_QUANTITY);
    var result = new StockDeduction();
    result.orderId = Objects.requireNonNull(orderId);
    result.hubId = Objects.requireNonNull(hubId);
    result.productId = Objects.requireNonNull(productId);
    result.quantity = quantity;
    return result;
  }

  public static StockDeduction completed(UUID orderId, Stock stock, long quantity) {
    var result = request(orderId, stock.getHubId(), stock.getProductId(), quantity);
    result.stockId = stock.getId();
    result.remainingQuantity = stock.getQuantity();
    return result;
  }

  public static StockDeduction rejected(
      UUID orderId, UUID hubId, UUID productId, long quantity, StockErrorCode code) {
    if (code != StockErrorCode.STOCK_NOT_FOUND && code != StockErrorCode.INSUFFICIENT_STOCK) {
      throw new IllegalArgumentException("Only definite deduction rejections may be persisted");
    }
    var result = request(orderId, hubId, productId, quantity);
    result.failureCode = code;
    return result;
  }

  public void requireSameRequest(UUID hub, UUID product, long amount) {
    if (!hubId.equals(hub) || !productId.equals(product) || quantity != amount) {
      throw new StockBusinessException(StockErrorCode.STOCK_DEDUCTION_CONFLICT);
    }
  }

  public void requireRestorable(UUID hub, UUID product, long amount) {
    if (failureCode != null)
      throw new StockBusinessException(StockErrorCode.STOCK_DEDUCTION_REJECTED);
    if (!hubId.equals(hub) || !productId.equals(product) || quantity != amount) {
      throw new StockBusinessException(StockErrorCode.STOCK_RESTORATION_CONFLICT);
    }
  }
}
