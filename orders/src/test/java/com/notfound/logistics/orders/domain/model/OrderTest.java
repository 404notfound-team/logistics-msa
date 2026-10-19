package com.notfound.logistics.orders.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.notfound.logistics.orders.domain.exception.OrderBusinessException;
import java.math.BigInteger;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderTest {
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
    assertThat(order.getRecoveryAction()).isEqualTo(RecoveryAction.CREATE);
    assertThatThrownBy(order::cancel).isInstanceOf(OrderBusinessException.class);
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
  void recoveryPreservesUncertainDeliveryPhase() {
    Order order = order(1L, 1);
    order.recordAttempt("DELIVERY_RESULT_UNKNOWN");
    order.requireRecovery("UPSTREAM_UNAVAILABLE");
    assertThat(order.getStatus()).isEqualTo(OrderStatus.RECOVERY_REQUIRED);
    assertThat(order.getRecoveryAction()).isEqualTo(RecoveryAction.CREATE);
    assertThat(order.getLastError()).isEqualTo("DELIVERY_RESULT_UNKNOWN");
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
