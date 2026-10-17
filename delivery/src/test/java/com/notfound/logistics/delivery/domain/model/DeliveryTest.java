package com.notfound.logistics.delivery.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.notfound.logistics.delivery.domain.exception.DeliveryBusinessException;
import com.notfound.logistics.delivery.domain.exception.DeliveryErrorCode;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

class DeliveryTest {
  private static final String ACTOR = "orders-user";
  private static final Instant DELETED_AT = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void waitingDeliveryIsCancelled() {
    Delivery delivery = delivery(DeliveryStatus.HUB_WAITING);

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
      names = {"HUB_WAITING", "CANCELLED"},
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

  @ParameterizedTest
  @EnumSource(
      value = DeliveryStatus.class,
      names = {"DELIVERED", "CANCELLED"})
  void finishedDeliveryIsDeletedWithTheActorAndTime(DeliveryStatus status) {
    Delivery delivery = delivery(status);

    delivery.delete(ACTOR, DELETED_AT);

    assertThat(delivery.getDeletedAt()).isEqualTo(DELETED_AT);
    assertThat(delivery.getDeletedBy()).isEqualTo(ACTOR);
    assertThat(delivery.getStatus()).isEqualTo(status);
  }

  @ParameterizedTest
  @EnumSource(
      value = DeliveryStatus.class,
      names = {"DELIVERED", "CANCELLED"},
      mode = EnumSource.Mode.EXCLUDE)
  void deliveryInProgressCannotBeDeleted(DeliveryStatus status) {
    Delivery delivery = delivery(status);

    assertThatThrownBy(() -> delivery.delete(ACTOR, DELETED_AT))
        .isInstanceOfSatisfying(
            DeliveryBusinessException.class,
            exception ->
                assertThat(exception.getErrorCode())
                    .isEqualTo(DeliveryErrorCode.DELIVERY_IN_PROGRESS));
    assertThat(delivery.getDeletedAt()).isNull();
    assertThat(delivery.getDeletedBy()).isNull();
  }

  @Test
  void deletingADeletedDeliveryAgainKeepsTheFirstDeletion() {
    Delivery delivery = delivery(DeliveryStatus.DELIVERED);
    delivery.delete(ACTOR, DELETED_AT);

    delivery.delete("other-user", DELETED_AT.plusSeconds(60));

    assertThat(delivery.getDeletedAt()).isEqualTo(DELETED_AT);
    assertThat(delivery.getDeletedBy()).isEqualTo(ACTOR);
  }

  private static Delivery delivery(DeliveryStatus status) {
    Delivery delivery =
        Delivery.create(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "부산시 해운대구 1", "홍길동");
    ReflectionTestUtils.setField(delivery, "status", status);
    return delivery;
  }
}
