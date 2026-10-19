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
import java.math.BigInteger;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.ColumnDefault;
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
  private static final String STOCK_RESTORATION_PENDING = "STOCK_RESTORATION_PENDING";
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

  @Column(nullable = false, updatable = false)
  private Long unitPrice;

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

  // null은 기존 주문이다. 과거 차감 여부를 추정해 자동 재차감하지 않는다.
  @Column(updatable = false)
  private Integer stockContractVersion;

  @Enumerated(EnumType.STRING)
  @Column(length = 32)
  private RecoveryPhase recoveryPhase;

  // 단계별 실패 횟수와 자동 복구 중단 여부를 재시작 후에도 유지한다.
  @ColumnDefault("0")
  @Column(nullable = false)
  private int recoveryAttempts;

  @ColumnDefault("false")
  @Column(nullable = false)
  private boolean recoveryBlocked;

  public static Order create(
      UUID receiverCompanyId,
      UUID supplierCompanyId,
      UUID productId,
      long quantity,
      String productName,
      Long unitPrice,
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
        || unitPrice < 0
        || departureHubId == null
        || destinationHubId == null
        || !validText(deliveryAddress, 255)
        || !validText(recipientName, 100)
        || idempotencyKey == null) {
      throw new OrderBusinessException(OrderErrorCode.INVALID_ORDER_INPUT);
    }
    Order order = new Order();
    // 최초 커밋 전에 진행 중 작업을 등록할 수 있도록 ID를 미리 생성한다.
    order.id = UUID.randomUUID();
    order.status = OrderStatus.PROCESSING;
    order.recoveryAction = RecoveryAction.CREATE;
    order.stockContractVersion = 2;
    order.recoveryPhase = RecoveryPhase.STOCK_DEDUCTION;
    order.receiverCompanyId = receiverCompanyId;
    order.supplierCompanyId = supplierCompanyId;
    order.productId = productId;
    order.quantity = quantity;
    order.productName = productName;
    order.unitPrice = unitPrice;
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

  public BigInteger totalPrice() {
    return BigInteger.valueOf(unitPrice).multiply(BigInteger.valueOf(quantity));
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
    clearRecovery();
  }

  public void recordAttempt(RecoveryPhase nextPhase) {
    if (status != OrderStatus.PROCESSING && status != OrderStatus.CANCELLING)
      throw new OrderBusinessException(OrderErrorCode.ORDER_STATE_CONFLICT);
    phase(Objects.requireNonNull(nextPhase));
  }

  public void requireRecovery(String code) {
    if (status != OrderStatus.PROCESSING && status != OrderStatus.CANCELLING)
      throw new OrderBusinessException(OrderErrorCode.ORDER_STATE_CONFLICT);
    // 기존 주문의 오류 표식은 진단 사유를 바꾸기 전에 명시적인 단계로 고정한다.
    phase(resumePhase());
    status = OrderStatus.RECOVERY_REQUIRED;
    lastError = code;
  }

  public boolean usesRecordedStockContract() {
    return Integer.valueOf(2).equals(stockContractVersion);
  }

  public RecoveryPhase resumePhase() {
    if (recoveryPhase != null) return recoveryPhase;
    // 기존 배송·취소 복구는 유지하되, 불명확한 과거 차감은 별도로 차단한다.
    if (recoveryAction == RecoveryAction.CANCEL) {
      return STOCK_RESTORATION_PENDING.equals(lastError)
          ? RecoveryPhase.STOCK_RESTORATION
          : RecoveryPhase.DELIVERY_CANCELLATION;
    }
    if ("DELIVERY_RESULT_UNKNOWN".equals(lastError)) return RecoveryPhase.DELIVERY_CREATION;
    if ("STOCK_RESULT_UNKNOWN".equals(lastError) || !usesRecordedStockContract())
      return RecoveryPhase.STOCK_DEDUCTION;
    return RecoveryPhase.UNKNOWN;
  }

  public void deferRecovery(String code, int maxAttempts) {
    requireRecovery(code);
    recoveryAttempts++;
    recoveryBlocked = recoveryAttempts >= maxAttempts;
  }

  public void blockRecovery(String code) {
    requireRecovery(code);
    recoveryBlocked = true;
  }

  public void beginCompensation() {
    require(OrderStatus.PROCESSING);
    phase(RecoveryPhase.STOCK_COMPENSATION);
    lastError = "DELIVERY_CREATION_FAILED";
  }

  public void completeCompensation() {
    if (resumePhase() != RecoveryPhase.STOCK_COMPENSATION)
      throw new OrderBusinessException(OrderErrorCode.ORDER_STATE_CONFLICT);
    fail("DELIVERY_CREATION_FAILED");
  }

  private void phase(RecoveryPhase phase) {
    // 실제 업무 단계가 바뀔 때만 재시도 예산을 초기화한다.
    if (recoveryPhase != phase) {
      recoveryPhase = phase;
      recoveryAttempts = 0;
      recoveryBlocked = false;
      lastError = null;
    }
  }

  private void clearRecovery() {
    recoveryPhase = null;
    recoveryAttempts = 0;
    recoveryBlocked = false;
  }

  public void cancel() {
    require(OrderStatus.CANCELLING);
    if (!awaitingStockRestoration())
      throw new OrderBusinessException(OrderErrorCode.ORDER_STATE_CONFLICT);
    finish(OrderStatus.CANCELLED);
  }

  public void awaitStockRestoration() {
    require(OrderStatus.CANCELLING);
    phase(RecoveryPhase.STOCK_RESTORATION);
  }

  public boolean awaitingStockRestoration() {
    return resumePhase() == RecoveryPhase.STOCK_RESTORATION;
  }

  public void rejectCancellation() {
    require(OrderStatus.CANCELLING);
    finish(OrderStatus.CONFIRMED);
  }

  private void finish(OrderStatus next) {
    status = next;
    recoveryAction = null;
    lastError = null;
    clearRecovery();
  }

  private void require(OrderStatus expected) {
    if (status != expected) throw new OrderBusinessException(OrderErrorCode.ORDER_STATE_CONFLICT);
  }

  private static boolean validText(String value, int maximum) {
    return value != null && !value.isBlank() && value.length() <= maximum;
  }
}
