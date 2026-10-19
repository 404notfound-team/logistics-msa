package com.notfound.logistics.orders.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.notfound.logistics.orders.domain.exception.OrderBusinessException;
import com.notfound.logistics.orders.domain.exception.OrderErrorCode;
import java.math.BigInteger;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

class OrderTest {
  @ParameterizedTest
  @CsvSource(
      nullValues = "NULL",
      textBlock =
          """
          PROCESSING, STOCK_DEDUCTION, true
          PROCESSING, DELIVERY_CREATION, true
          PROCESSING, STOCK_COMPENSATION, true
          PROCESSING, DELIVERY_CANCELLATION, false
          PROCESSING, STOCK_RESTORATION, false
          PROCESSING, NULL, false
          CANCELLING, STOCK_DEDUCTION, false
          CANCELLING, DELIVERY_CREATION, false
          CANCELLING, STOCK_COMPENSATION, false
          CANCELLING, DELIVERY_CANCELLATION, true
          CANCELLING, STOCK_RESTORATION, true
          CANCELLING, NULL, false
          RECOVERY_REQUIRED, STOCK_DEDUCTION, true
          RECOVERY_REQUIRED, DELIVERY_CREATION, true
          RECOVERY_REQUIRED, STOCK_COMPENSATION, true
          RECOVERY_REQUIRED, DELIVERY_CANCELLATION, true
          RECOVERY_REQUIRED, STOCK_RESTORATION, true
          RECOVERY_REQUIRED, NULL, false
          CONFIRMED, STOCK_DEDUCTION, false
          CONFIRMED, DELIVERY_CREATION, false
          CONFIRMED, STOCK_COMPENSATION, false
          CONFIRMED, DELIVERY_CANCELLATION, false
          CONFIRMED, STOCK_RESTORATION, false
          CONFIRMED, NULL, true
          CANCELLED, STOCK_DEDUCTION, false
          CANCELLED, DELIVERY_CREATION, false
          CANCELLED, STOCK_COMPENSATION, false
          CANCELLED, DELIVERY_CANCELLATION, false
          CANCELLED, STOCK_RESTORATION, false
          CANCELLED, NULL, true
          FAILED, STOCK_DEDUCTION, false
          FAILED, DELIVERY_CREATION, false
          FAILED, STOCK_COMPENSATION, false
          FAILED, DELIVERY_CANCELLATION, false
          FAILED, STOCK_RESTORATION, false
          FAILED, NULL, true
          """)
  void validatesEveryStatusAndPhaseCombination(
      OrderStatus status, RecoveryPhase phase, boolean consistent) {
    Order order = order(1L, 1);
    ReflectionTestUtils.setField(order, "status", status);
    ReflectionTestUtils.setField(order, "recoveryPhase", phase);
    assertThat(order.hasConsistentRecoveryPhase()).isEqualTo(consistent);
  }

  @ParameterizedTest
  @CsvSource(
      nullValues = "NULL",
      textBlock =
          """
          PROCESSING, DELIVERY_CANCELLATION
          PROCESSING, STOCK_RESTORATION
          PROCESSING, NULL
          CANCELLING, STOCK_DEDUCTION
          CANCELLING, DELIVERY_CREATION
          CANCELLING, STOCK_COMPENSATION
          CANCELLING, NULL
          """)
  void rejectsIncompatiblePhaseChangesWithoutMutatingTheOrder(
      OrderStatus status, RecoveryPhase requested) {
    Order order = order(1L, 1);
    RecoveryPhase previous =
        status == OrderStatus.CANCELLING
            ? RecoveryPhase.DELIVERY_CANCELLATION
            : RecoveryPhase.STOCK_DEDUCTION;
    ReflectionTestUtils.setField(order, "status", status);
    ReflectionTestUtils.setField(order, "recoveryPhase", previous);
    ReflectionTestUtils.setField(order, "recoveryAttempts", 3);
    ReflectionTestUtils.setField(order, "lastError", "PREVIOUS_FAILURE");
    assertThatThrownBy(() -> order.recordAttempt(requested))
        .isInstanceOfSatisfying(
            OrderBusinessException.class,
            failure ->
                assertThat(failure.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_STATE_CONFLICT));
    assertThat(order.getStatus()).isEqualTo(status);
    assertThat(order.getRecoveryPhase()).isEqualTo(previous);
    assertThat(order.getRecoveryAttempts()).isEqualTo(3);
    assertThat(order.getLastError()).isEqualTo("PREVIOUS_FAILURE");
  }

  @ParameterizedTest
  @EnumSource(
      value = OrderStatus.class,
      names = {"PROCESSING", "CANCELLING", "RECOVERY_REQUIRED"})
  void blocksInconsistentPendingOrdersOnceWithoutInventingAPhase(OrderStatus status) {
    Order order = order(1L, 1);
    ReflectionTestUtils.setField(order, "status", status);
    ReflectionTestUtils.setField(order, "recoveryPhase", null);
    ReflectionTestUtils.setField(order, "recoveryAttempts", 3);
    order.blockRecovery("INCONSISTENT_RECOVERY_PHASE");
    order.blockRecovery("SHOULD_NOT_OVERWRITE_THE_FIRST_REASON");
    assertThat(order.getStatus()).isEqualTo(OrderStatus.RECOVERY_REQUIRED);
    assertThat(order.isRecoveryBlocked()).isTrue();
    assertThat(order.getLastError()).isEqualTo("INCONSISTENT_RECOVERY_PHASE");
    assertThat(order.getRecoveryPhase()).isNull();
    assertThat(order.getRecoveryAttempts()).isEqualTo(3);
  }

  @ParameterizedTest
  @EnumSource(
      value = OrderStatus.class,
      names = {"CONFIRMED", "CANCELLED", "FAILED"})
  void neverReopensCompletedOrdersWhenBlockingRecovery(OrderStatus status) {
    Order order = order(1L, 1);
    ReflectionTestUtils.setField(order, "status", status);
    ReflectionTestUtils.setField(order, "recoveryPhase", null);
    assertThatThrownBy(() -> order.blockRecovery("INCONSISTENT_RECOVERY_PHASE"))
        .isInstanceOf(OrderBusinessException.class);
    assertThat(order.getStatus()).isEqualTo(status);
    assertThat(order.isRecoveryBlocked()).isFalse();
    assertThat(order.hasConsistentRecoveryPhase()).isTrue();
  }

  @ParameterizedTest
  @EnumSource(
      value = OrderStatus.class,
      names = {"CONFIRMED", "CANCELLED", "FAILED"})
  void completedTransitionsClearRecoveryMetadata(OrderStatus terminal) {
    Order order = order(1L, 1);
    ReflectionTestUtils.setField(order, "recoveryAttempts", 3);
    if (terminal == OrderStatus.CONFIRMED) order.confirm(UUID.randomUUID());
    else if (terminal == OrderStatus.FAILED) order.fail("INSUFFICIENT_STOCK");
    else {
      ReflectionTestUtils.setField(order, "status", OrderStatus.CANCELLING);
      ReflectionTestUtils.setField(order, "recoveryPhase", RecoveryPhase.STOCK_RESTORATION);
      order.cancel();
    }
    assertThat(order.getStatus()).isEqualTo(terminal);
    assertThat(order.getRecoveryPhase()).isNull();
    assertThat(order.getRecoveryAttempts()).isZero();
    assertThat(order.isRecoveryBlocked()).isFalse();
    assertThat(order.hasConsistentRecoveryPhase()).isTrue();
  }

  @Test
  void supportsTheCompanyIntegerPriceRangeAndExactTotalsBeyondLong() {
    assertThatThrownBy(() -> order(-1L, 1)).isInstanceOf(OrderBusinessException.class);
    assertThatThrownBy(() -> order(null, 1)).isInstanceOf(OrderBusinessException.class);
    assertThat(order(0L, Long.MAX_VALUE).totalPrice()).isEqualTo(BigInteger.ZERO);
    assertThat(order(Long.MAX_VALUE, Long.MAX_VALUE).totalPrice())
        .isEqualTo(BigInteger.valueOf(Long.MAX_VALUE).pow(2));
  }

  @Test
  void onlyAcknowledgedCreationCanBecomeConfirmedAndTerminalStateCannotBeReused() {
    Order order = order(0L, 1);
    assertThat(order.getStatus()).isEqualTo(OrderStatus.PROCESSING);
    assertThat(order.getRecoveryPhase()).isEqualTo(RecoveryPhase.STOCK_DEDUCTION);
    assertThatThrownBy(order::cancel).isInstanceOf(OrderBusinessException.class);
    assertThatThrownBy(() -> order.confirm(null)).isInstanceOf(OrderBusinessException.class);
    UUID delivery = UUID.randomUUID();
    order.confirm(delivery);
    assertThat(order.getDeliveryId()).isEqualTo(delivery);
    assertThat(order.getRecoveryPhase()).isNull();
    assertThatThrownBy(() -> order.requireRecovery("UNKNOWN"))
        .isInstanceOf(OrderBusinessException.class);
    assertThatThrownBy(() -> order.fail("INSUFFICIENT_STOCK"))
        .isInstanceOf(OrderBusinessException.class);
  }

  @Test
  void recoveryPreservesUncertainDeliveryPhase() {
    Order order = order(1L, 1);
    order.recordAttempt(RecoveryPhase.DELIVERY_CREATION);
    order.requireRecovery("UPSTREAM_UNAVAILABLE");
    assertThat(order.getStatus()).isEqualTo(OrderStatus.RECOVERY_REQUIRED);
    assertThat(order.getRecoveryPhase()).isEqualTo(RecoveryPhase.DELIVERY_CREATION);
    assertThat(order.getLastError()).isEqualTo("UPSTREAM_UNAVAILABLE");
  }

  @Test
  void exhaustedRetriesPreserveThePhaseAndUncertainOutcome() {
    Order order = order(1L, 1);
    for (int i = 0; i < 8; i++) {
      order.deferRecovery("TIMEOUT", 8);
      assertThat(order.getRecoveryPhase()).isEqualTo(RecoveryPhase.STOCK_DEDUCTION);
      if (i < 7) {
        org.springframework.test.util.ReflectionTestUtils.setField(
            order, "status", OrderStatus.PROCESSING);
      }
    }
    assertThat(order.isRecoveryBlocked()).isTrue();
    assertThat(order.getStatus()).isEqualTo(OrderStatus.RECOVERY_REQUIRED);
    assertThat(order.getRecoveryPhase()).isEqualTo(RecoveryPhase.STOCK_DEDUCTION);
  }

  @Test
  void movingToTheDeliveryPhaseResetsThePreviousRetryBudget() {
    Order order = order(1L, 1);
    order.deferRecovery("TIMEOUT", 20);
    org.springframework.test.util.ReflectionTestUtils.setField(
        order, "status", OrderStatus.PROCESSING);
    order.recordAttempt(RecoveryPhase.DELIVERY_CREATION);
    assertThat(order.getRecoveryAttempts()).isZero();
    assertThat(order.getLastError()).isNull();
    assertThat(order.getRecoveryPhase()).isEqualTo(RecoveryPhase.DELIVERY_CREATION);
  }

  private Order order(Long price, long quantity) {
    return Order.create(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        quantity,
        "상품",
        price,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "주소",
        "수령인",
        null,
        UUID.randomUUID());
  }
}
