package com.notfound.logistics.delivery.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.notfound.logistics.delivery.domain.exception.DeliveryBusinessException;
import com.notfound.logistics.delivery.domain.exception.DeliveryErrorCode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

class DeliveryTest {
  private static final UUID ASSIGNEE_ID = UUID.fromString("00000000-0000-0000-0000-000000000007");
  private static final UUID OTHER_ASSIGNEE_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000008");

  @Test
  void newDeliveryWaitsAtTheHubWithoutAnAssignee() {
    Delivery delivery = delivery();

    assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.HUB_WAITING);
    assertThat(delivery.getAssigneeId()).isNull();
  }

  @ParameterizedTest
  @CsvSource({
    "HUB_WAITING, HUB_MOVING",
    "HUB_MOVING, DESTINATION_HUB_ARRIVED",
    "DESTINATION_HUB_ARRIVED, COMPANY_MOVING",
    "COMPANY_MOVING, DELIVERED"
  })
  void statusMovesForwardOneStepAtATime(DeliveryStatus from, DeliveryStatus to) {
    Delivery delivery = deliveryIn(from);

    delivery.changeStatus(to);

    assertThat(delivery.getStatus()).isEqualTo(to);
  }

  @ParameterizedTest
  @CsvSource({
    // 건너뛰기
    "HUB_WAITING, DESTINATION_HUB_ARRIVED",
    "HUB_WAITING, DELIVERED",
    "HUB_MOVING, COMPANY_MOVING",
    "DESTINATION_HUB_ARRIVED, DELIVERED",
    // 되돌리기
    "HUB_MOVING, HUB_WAITING",
    "COMPANY_MOVING, DESTINATION_HUB_ARRIVED",
    "DELIVERED, HUB_WAITING",
    "DELIVERED, COMPANY_MOVING"
  })
  void skippingOrGoingBackIsRejectedAndKeepsTheCurrentStatus(
      DeliveryStatus from, DeliveryStatus to) {
    Delivery delivery = deliveryIn(from);

    assertThatThrownBy(() -> delivery.changeStatus(to))
        .isInstanceOf(DeliveryBusinessException.class)
        .extracting(exception -> ((DeliveryBusinessException) exception).getErrorCode())
        .isEqualTo(DeliveryErrorCode.INVALID_STATUS_TRANSITION);
    assertThat(delivery.getStatus()).isEqualTo(from);
  }

  @ParameterizedTest
  @EnumSource(DeliveryStatus.class)
  void changingToTheCurrentStatusChangesNothing(DeliveryStatus status) {
    Delivery delivery = deliveryIn(status);

    delivery.changeStatus(status);

    assertThat(delivery.getStatus()).isEqualTo(status);
  }

  @Test
  void nullStatusIsRejectedAndKeepsTheCurrentStatus() {
    Delivery delivery = delivery();

    assertThatThrownBy(() -> delivery.changeStatus(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("status");
    assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.HUB_WAITING);
  }

  @Test
  void hubWaitingDeliveryCanBeAssigned() {
    Delivery delivery = delivery();

    delivery.assign(ASSIGNEE_ID);

    assertThat(delivery.getAssigneeId()).isEqualTo(ASSIGNEE_ID);
  }

  @Test
  void hubWaitingDeliveryCanBeReassignedToAnotherAssignee() {
    Delivery delivery = delivery();
    delivery.assign(ASSIGNEE_ID);

    delivery.assign(OTHER_ASSIGNEE_ID);

    assertThat(delivery.getAssigneeId()).isEqualTo(OTHER_ASSIGNEE_ID);
  }

  @ParameterizedTest
  @EnumSource(value = DeliveryStatus.class, names = "HUB_WAITING", mode = EnumSource.Mode.EXCLUDE)
  void deliveryThatLeftHubWaitingCannotBeReassignedAndKeepsItsAssignee(DeliveryStatus status) {
    Delivery delivery = delivery();
    delivery.assign(ASSIGNEE_ID);
    advance(delivery, status);

    assertThatThrownBy(() -> delivery.assign(OTHER_ASSIGNEE_ID))
        .isInstanceOf(DeliveryBusinessException.class)
        .extracting(exception -> ((DeliveryBusinessException) exception).getErrorCode())
        .isEqualTo(DeliveryErrorCode.ASSIGNMENT_NOT_ALLOWED);
    assertThat(delivery.getAssigneeId()).isEqualTo(ASSIGNEE_ID);
  }

  @ParameterizedTest
  @EnumSource(value = DeliveryStatus.class, names = "HUB_WAITING", mode = EnumSource.Mode.EXCLUDE)
  void unassignedDeliveryThatLeftHubWaitingCannotBeAssigned(DeliveryStatus status) {
    Delivery delivery = deliveryIn(status);

    assertThatThrownBy(() -> delivery.assign(ASSIGNEE_ID))
        .isInstanceOf(DeliveryBusinessException.class)
        .extracting(exception -> ((DeliveryBusinessException) exception).getErrorCode())
        .isEqualTo(DeliveryErrorCode.ASSIGNMENT_NOT_ALLOWED);
    assertThat(delivery.getAssigneeId()).isNull();
  }

  @Test
  void nullAssigneeIsRejectedAndKeepsTheCurrentAssignee() {
    Delivery delivery = delivery();
    delivery.assign(ASSIGNEE_ID);

    assertThatThrownBy(() -> delivery.assign(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("assigneeId");
    assertThat(delivery.getAssigneeId()).isEqualTo(ASSIGNEE_ID);
  }

  private static Delivery deliveryIn(DeliveryStatus status) {
    Delivery delivery = delivery();
    advance(delivery, status);
    return delivery;
  }

  /** 허용된 전이만 거쳐 target 상태까지 진행한다. */
  private static void advance(Delivery delivery, DeliveryStatus target) {
    for (DeliveryStatus status : DeliveryStatus.values()) {
      if (status.ordinal() > target.ordinal()) {
        return;
      }
      delivery.changeStatus(status);
    }
  }

  private static Delivery delivery() {
    return Delivery.create(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "서울특별시 송파구 송파대로 55", "홍길동");
  }


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

  private static Delivery delivery(DeliveryStatus status) {
    Delivery delivery =
        Delivery.create(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "부산시 해운대구 1", "홍길동");
    ReflectionTestUtils.setField(delivery, "status", status);
    return delivery;
  }
}
