package com.notfound.logistics.hub.domain.model.stock;

import com.notfound.logistics.common.audit.AuditEntity;
import com.notfound.logistics.hub.domain.exception.stock.StockBusinessException;
import com.notfound.logistics.hub.domain.exception.stock.StockErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.SQLRestriction;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLRestriction("deleted_at IS NULL")
@Check(constraints = "quantity >= 0")
@Table(
    name = "p_stocks",
    schema = "hub",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uk_stocks_hub_product",
          columnNames = {"hub_id", "product_id"})
    })
public class Stock extends AuditEntity {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "hub_id", nullable = false, updatable = false)
  private UUID hubId;

  @Column(name = "product_id", nullable = false, updatable = false)
  private UUID productId;

  @Column(name = "quantity", nullable = false)
  private Long quantity;

  private Stock(UUID id, UUID hubId, UUID productId, long quantity) {

    this.id = Objects.requireNonNull(id, "재고 ID는 필수입니다.");
    this.hubId = Objects.requireNonNull(hubId, "허브 ID는 필수입니다.");
    this.productId = Objects.requireNonNull(productId, "상품 ID는 필수입니다.");

    if (quantity < 0) {
      throw new StockBusinessException(StockErrorCode.INVALID_INITIAL_STOCK);
    }

    this.quantity = quantity;
  }

  public static Stock create(UUID hubId, UUID productId, long initialQuantity) {

    return new Stock(UUID.randomUUID(), hubId, productId, initialQuantity);
  }

  /** 재고 추가 */
  public void add(long amount) {
    validatePositiveAmount(amount);

    try {
      this.quantity = Math.addExact(this.quantity, amount);
    } catch (ArithmeticException exception) {
      throw new StockBusinessException(StockErrorCode.STOCK_QUANTITY_OVERFLOW);
    }
  }

  /** 재고 차감 */
  public void deduct(long amount) {
    validatePositiveAmount(amount);

    if (this.quantity < amount) {
      throw new StockBusinessException(StockErrorCode.INSUFFICIENT_STOCK);
    }

    this.quantity -= amount;
  }

  /** 재고 복원 */
  public void restore(long amount) {
    add(amount);
  }

  private void validatePositiveAmount(long amount) {
    if (amount <= 0) {
      throw new StockBusinessException(StockErrorCode.INVALID_STOCK_QUANTITY);
    }
  }
}
