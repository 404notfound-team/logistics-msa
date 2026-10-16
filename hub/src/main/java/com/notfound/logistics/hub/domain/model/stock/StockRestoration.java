package com.notfound.logistics.hub.domain.model.stock;

import com.notfound.logistics.common.audit.AuditEntity;
import com.notfound.logistics.hub.domain.exception.stock.StockBusinessException;
import com.notfound.logistics.hub.domain.exception.stock.StockErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.SQLRestriction;

/** Immutable successful restoration receipt, committed atomically with the stock increment. */
@Entity
@Table(name = "p_stock_restorations", schema = "hub")
@Check(constraints = "quantity > 0 AND remaining_quantity >= 0")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockRestoration extends AuditEntity {
  @Id private UUID orderId;

  @Column(nullable = false, updatable = false)
  private UUID hubId;

  @Column(nullable = false, updatable = false)
  private UUID productId;

  @Column(nullable = false, updatable = false)
  private long quantity;

  @Column(nullable = false, updatable = false)
  private UUID stockId;

  @Column(nullable = false, updatable = false)
  private long remainingQuantity;

  public static StockRestoration completed(UUID orderId, Stock stock, long quantity) {
    var receipt = new StockRestoration();
    receipt.orderId = Objects.requireNonNull(orderId);
    receipt.hubId = stock.getHubId();
    receipt.productId = stock.getProductId();
    receipt.quantity = quantity;
    receipt.stockId = stock.getId();
    receipt.remainingQuantity = stock.getQuantity();
    return receipt;
  }

  public void requireSameRequest(UUID hub, UUID product, long amount) {
    if (!hubId.equals(hub) || !productId.equals(product) || quantity != amount)
      throw new StockBusinessException(StockErrorCode.STOCK_RESTORATION_CONFLICT);
  }
}
