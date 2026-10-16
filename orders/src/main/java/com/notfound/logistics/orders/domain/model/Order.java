package com.notfound.logistics.orders.domain.model;

import com.notfound.logistics.common.audit.AuditEntity;
import com.notfound.logistics.orders.domain.exception.OrderBusinessException;
import com.notfound.logistics.orders.domain.exception.OrderErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.SQLRestriction;

@Entity
@Table(
    name = "p_orders",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_orders_created_by_idempotency_key",
            columnNames = {"created_by", "idempotency_key"}))
@Check(constraints = "quantity > 0 AND unit_price >= 0")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order extends AuditEntity {
  @Id private UUID id;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private OrderStatus status;

  @Column(nullable = false, updatable = false)
  private UUID receiverCompanyId;

  @Column(nullable = false, updatable = false)
  private UUID supplierCompanyId;

  @Column(nullable = false, updatable = false)
  private UUID productId;

  @Column(nullable = false, updatable = false)
  private long quantity;

  @Column(nullable = false, updatable = false, length = 100)
  private String productName;

  @Column(nullable = false, updatable = false, precision = 15, scale = 2)
  private BigDecimal unitPrice;

  @Column(nullable = false, updatable = false)
  private UUID departureHubId;

  @Column(nullable = false, updatable = false)
  private UUID destinationHubId;

  @Column(nullable = false, updatable = false)
  private String deliveryAddress;

  @Column(nullable = false, updatable = false, length = 100)
  private String recipientName;

  @Column(columnDefinition = "text", updatable = false)
  private String requestNotes;

  @Column(nullable = false, updatable = false)
  private UUID idempotencyKey;

  private UUID deliveryId;

  @Column(length = 255)
  private String lastError;

  @Enumerated(EnumType.STRING)
  @Column(length = 10)
  private RecoveryAction recoveryAction;

  public static Order create(
      UUID receiverCompanyId,
      UUID supplierCompanyId,
      UUID productId,
      long quantity,
      String productName,
      BigDecimal unitPrice,
      UUID departureHubId,
      UUID destinationHubId,
      String deliveryAddress,
      String recipientName,
      String requestNotes,
      UUID idempotencyKey) {
    if (receiverCompanyId == null
        || supplierCompanyId == null
        || productId == null
        || quantity <= 0
        || !validText(productName, 100)
        || unitPrice == null
        || unitPrice.signum() < 0
        || departureHubId == null
        || destinationHubId == null
        || !validText(deliveryAddress, 255)
        || !validText(recipientName, 100)
        || idempotencyKey == null) {
      throw new OrderBusinessException(OrderErrorCode.INVALID_ORDER_INPUT);
    }
    BigDecimal price;
    try {
      price = unitPrice.setScale(2, RoundingMode.UNNECESSARY);
    } catch (ArithmeticException exception) {
      throw new OrderBusinessException(OrderErrorCode.INVALID_ORDER_INPUT);
    }
    if (price.precision() > 15)
      throw new OrderBusinessException(OrderErrorCode.INVALID_ORDER_INPUT);
    Order order = new Order();
    // Allocate before persistence so processing is registered before the first commit.
    order.id = UUID.randomUUID();
    order.status = OrderStatus.PROCESSING;
    order.recoveryAction = RecoveryAction.CREATE;
    order.receiverCompanyId = receiverCompanyId;
    order.supplierCompanyId = supplierCompanyId;
    order.productId = productId;
    order.quantity = quantity;
    order.productName = productName;
    order.unitPrice = price;
    order.departureHubId = departureHubId;
    order.destinationHubId = destinationHubId;
    order.deliveryAddress = deliveryAddress;
    order.recipientName = recipientName;
    order.requestNotes = requestNotes;
    order.idempotencyKey = idempotencyKey;
    return order;
  }

  public boolean matches(
      UUID receiver,
      UUID supplier,
      UUID product,
      long requestedQuantity,
      String address,
      String recipient,
      String notes) {
    return Objects.equals(receiverCompanyId, receiver)
        && Objects.equals(supplierCompanyId, supplier)
        && Objects.equals(productId, product)
        && quantity == requestedQuantity
        && Objects.equals(deliveryAddress, address)
        && Objects.equals(recipientName, recipient)
        && Objects.equals(requestNotes, notes);
  }

  public BigDecimal totalPrice() {
    return unitPrice.multiply(BigDecimal.valueOf(quantity));
  }

  public void confirm(UUID confirmedDeliveryId) {
    require(OrderStatus.PROCESSING);
    if (confirmedDeliveryId == null)
      throw new OrderBusinessException(OrderErrorCode.ORDER_STATE_CONFLICT);
    deliveryId = confirmedDeliveryId;
    finish(OrderStatus.CONFIRMED);
  }

  public void fail(String errorCode) {
    require(OrderStatus.PROCESSING);
    lastError = Objects.requireNonNull(errorCode);
    status = OrderStatus.FAILED;
    recoveryAction = null;
  }

  public void recordAttempt(String code) {
    if (status != OrderStatus.PROCESSING && status != OrderStatus.CANCELLING)
      throw new OrderBusinessException(OrderErrorCode.ORDER_STATE_CONFLICT);
    lastError = code;
  }

  public void requireRecovery(String code) {
    if (status != OrderStatus.PROCESSING && status != OrderStatus.CANCELLING)
      throw new OrderBusinessException(OrderErrorCode.ORDER_STATE_CONFLICT);
    status = OrderStatus.RECOVERY_REQUIRED;
    // Keep the uncertain delivery phase across restarts.
    if (lastError == null) lastError = code;
  }

  private void finish(OrderStatus next) {
    status = next;
    recoveryAction = null;
    lastError = null;
  }

  private void require(OrderStatus expected) {
    if (status != expected) throw new OrderBusinessException(OrderErrorCode.ORDER_STATE_CONFLICT);
  }

  private static boolean validText(String value, int maximum) {
    return value != null && !value.isBlank() && value.length() <= maximum;
  }
}
