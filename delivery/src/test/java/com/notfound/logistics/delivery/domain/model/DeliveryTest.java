package com.notfound.logistics.delivery.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.notfound.logistics.delivery.domain.exception.DeliveryBusinessException;
import com.notfound.logistics.delivery.domain.exception.DeliveryErrorCode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

class DeliveryTest {

  @Test
  void waitingDeliveryIsCancelled() {
    Delivery delivery = delivery(DeliveryStatus.WAITING);

    delivery.cancel();

    assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.CANCELLED);
  }

  @Test
  void cancellingACancelledDeliveryAgainKeepsItCancelled() {
    Delivery delivery = delivery(DeliveryStatus.CANCELLED);

    delivery.cancel();

    assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.CANCELLED);
  }

  @ParameterizedTest
  @EnumSource(
      value = DeliveryStatus.class,
      names = {"WAITING", "CANCELLED"},
      mode = EnumSource.Mode.EXCLUDE)
  void startedDeliveryCannotBeCancelledAndKeepsItsStatus(DeliveryStatus status) {
    Delivery delivery = delivery(status);

    assertThatThrownBy(delivery::cancel)
        .isInstanceOfSatisfying(
            DeliveryBusinessException.class,
            exception ->
                assertThat(exception.getErrorCode())
                    .isEqualTo(DeliveryErrorCode.DELIVERY_ALREADY_STARTED));
    assertThat(delivery.getStatus()).isEqualTo(status);
  }

  private static Delivery delivery(DeliveryStatus status) {
    Delivery delivery =
        Delivery.create(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "부산시 해운대구 1", "홍길동");
    ReflectionTestUtils.setField(delivery, "status", status);
    return delivery;
  }
}
