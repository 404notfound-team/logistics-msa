package com.notfound.logistics.delivery.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.notfound.logistics.delivery.domain.exception.DeliveryBusinessException;
import com.notfound.logistics.delivery.domain.exception.DeliveryErrorCode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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
  @EnumSource(DeliveryStatus.class)
  void statusCanBeChangedToAnyStatus(DeliveryStatus status) {
    Delivery delivery = delivery();

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
    delivery.changeStatus(status);

    assertThatThrownBy(() -> delivery.assign(OTHER_ASSIGNEE_ID))
        .isInstanceOf(DeliveryBusinessException.class)
        .extracting(exception -> ((DeliveryBusinessException) exception).getErrorCode())
        .isEqualTo(DeliveryErrorCode.ASSIGNMENT_NOT_ALLOWED);
    assertThat(delivery.getAssigneeId()).isEqualTo(ASSIGNEE_ID);
  }

  @ParameterizedTest
  @EnumSource(value = DeliveryStatus.class, names = "HUB_WAITING", mode = EnumSource.Mode.EXCLUDE)
  void unassignedDeliveryThatLeftHubWaitingCannotBeAssigned(DeliveryStatus status) {
    Delivery delivery = delivery();
    delivery.changeStatus(status);

    assertThatThrownBy(() -> delivery.assign(ASSIGNEE_ID))
        .isInstanceOf(DeliveryBusinessException.class)
        .extracting(exception -> ((DeliveryBusinessException) exception).getErrorCode())
        .isEqualTo(DeliveryErrorCode.ASSIGNMENT_NOT_ALLOWED);
    assertThat(delivery.getAssigneeId()).isNull();
  }

  @Test
  void deliveryReturnedToHubWaitingCanBeAssignedAgain() {
    Delivery delivery = delivery();
    delivery.changeStatus(DeliveryStatus.HUB_MOVING);
    delivery.changeStatus(DeliveryStatus.HUB_WAITING);

    delivery.assign(ASSIGNEE_ID);

    assertThat(delivery.getAssigneeId()).isEqualTo(ASSIGNEE_ID);
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

  private static Delivery delivery() {
    return Delivery.create(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "서울특별시 송파구 송파대로 55", "홍길동");
  }
}
