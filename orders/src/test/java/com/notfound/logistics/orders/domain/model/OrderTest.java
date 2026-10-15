package com.notfound.logistics.orders.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.notfound.logistics.orders.domain.exception.OrderBusinessException;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderTest {
  @Test
  void rejectsMoneyThatWouldBeRoundedOrOverflowTheDeclaredColumn() {
    for (String price : new String[] {"-1", "1.001", "10000000000000.00"}) {
      assertThatThrownBy(() -> order(new BigDecimal(price), 1))
          .isInstanceOf(OrderBusinessException.class);
    }
    assertThat(order(new BigDecimal("9999999999999.99"), Long.MAX_VALUE).totalPrice())
        .isEqualByComparingTo(
            new BigDecimal("9999999999999.99").multiply(BigDecimal.valueOf(Long.MAX_VALUE)));
  }

  @Test
  void onlyAcknowledgedCreationCanBecomeConfirmedAndTerminalStateCannotBeReused() {
    Order order = order(BigDecimal.ZERO, 1);
    assertThat(order.getStatus()).isEqualTo(OrderStatus.PROCESSING);
    assertThat(order.getRecoveryAction()).isEqualTo(RecoveryAction.CREATE);
    assertThatThrownBy(() -> order.confirm(null)).isInstanceOf(OrderBusinessException.class);
    UUID delivery = UUID.randomUUID();
    order.confirm(delivery);
    assertThat(order.getDeliveryId()).isEqualTo(delivery);
    assertThat(order.getRecoveryAction()).isNull();
    assertThatThrownBy(() -> order.requireRecovery("UNKNOWN"))
        .isInstanceOf(OrderBusinessException.class);
    assertThatThrownBy(() -> order.fail("INSUFFICIENT_STOCK"))
        .isInstanceOf(OrderBusinessException.class);
  }

  @Test
  void uncertainRestorationPreservesKnownCompensationIntent() {
    Order order = order(BigDecimal.ONE, 1);
    order.recordAttempt("DELIVERY_CREATION_FAILED");
    order.requireRecovery("UPSTREAM_UNAVAILABLE");
    assertThat(order.getStatus()).isEqualTo(OrderStatus.RECOVERY_REQUIRED);
    assertThat(order.getRecoveryAction()).isEqualTo(RecoveryAction.CREATE);
    assertThat(order.getLastError()).isEqualTo("DELIVERY_CREATION_FAILED");
  }

  private Order order(BigDecimal price, long quantity) {
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
