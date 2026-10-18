package com.notfound.logistics.delivery.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import com.notfound.logistics.delivery.application.dto.DeliveryResult;
import com.notfound.logistics.delivery.application.dto.DeliveryRouteResult;
import com.notfound.logistics.delivery.domain.exception.DeliveryBusinessException;
import com.notfound.logistics.delivery.domain.exception.DeliveryErrorCode;
import com.notfound.logistics.delivery.domain.model.Delivery;
import com.notfound.logistics.delivery.domain.model.DeliveryRoute;
import com.notfound.logistics.delivery.domain.model.DeliveryRouteStatus;
import com.notfound.logistics.delivery.domain.model.DeliveryStatus;
import com.notfound.logistics.delivery.domain.repository.DeliveryRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeliveryCommandServiceTest {
  private static final UUID DELIVERY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
  private static final UUID ASSIGNEE_ID = UUID.fromString("00000000-0000-0000-0000-000000000007");
  private static final UUID OTHER_ASSIGNEE_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000008");

  private final DeliveryRepository deliveryRepository = mock(DeliveryRepository.class);
  private final DeliveryCommandService service = new DeliveryCommandService(deliveryRepository);

  @Test
  void modifyChangesOnlyTheStatusWhenNoAssigneeIsGiven() {
    Delivery delivery = existingDelivery();
    delivery.assign(ASSIGNEE_ID);

    DeliveryResult result = service.modify(DELIVERY_ID, DeliveryStatus.HUB_MOVING, null);

    assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.HUB_MOVING);
    assertThat(delivery.getAssigneeId()).isEqualTo(ASSIGNEE_ID);
    assertThat(result.status()).isEqualTo(DeliveryStatus.HUB_MOVING);
    assertThat(result.assigneeId()).isEqualTo(ASSIGNEE_ID);
  }

  @Test
  void modifyReassignsAHubWaitingDeliveryWithoutChangingItsStatus() {
    Delivery delivery = existingDelivery();
    delivery.assign(ASSIGNEE_ID);

    DeliveryResult result = service.modify(DELIVERY_ID, null, OTHER_ASSIGNEE_ID);

    assertThat(delivery.getAssigneeId()).isEqualTo(OTHER_ASSIGNEE_ID);
    assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.HUB_WAITING);
    assertThat(result.assigneeId()).isEqualTo(OTHER_ASSIGNEE_ID);
  }

  @Test
  void assignmentIsJudgedByTheCurrentStatusSoAssigningAndLeavingHubWaitingTogetherIsAllowed() {
    Delivery delivery = existingDelivery();

    DeliveryResult result = service.modify(DELIVERY_ID, DeliveryStatus.HUB_MOVING, ASSIGNEE_ID);

    assertThat(delivery.getAssigneeId()).isEqualTo(ASSIGNEE_ID);
    assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.HUB_MOVING);
    assertThat(result.assigneeId()).isEqualTo(ASSIGNEE_ID);
    assertThat(result.status()).isEqualTo(DeliveryStatus.HUB_MOVING);
  }

  @Test
  void reassigningADeliveryThatLeftHubWaitingFailsAndChangesNothing() {
    Delivery delivery = existingDelivery();
    delivery.assign(ASSIGNEE_ID);
    delivery.changeStatus(DeliveryStatus.HUB_MOVING);

    assertThatThrownBy(
            () -> service.modify(DELIVERY_ID, DeliveryStatus.HUB_WAITING, OTHER_ASSIGNEE_ID))
        .isInstanceOf(DeliveryBusinessException.class)
        .extracting(exception -> ((DeliveryBusinessException) exception).getErrorCode())
        .isEqualTo(DeliveryErrorCode.ASSIGNMENT_NOT_ALLOWED);
    assertThat(delivery.getAssigneeId()).isEqualTo(ASSIGNEE_ID);
    assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.HUB_MOVING);
  }

  @Test
  void skippingAStatusFailsWithInvalidStatusTransitionAndChangesNothing() {
    Delivery delivery = existingDelivery();

    assertThatThrownBy(() -> service.modify(DELIVERY_ID, DeliveryStatus.DELIVERED, null))
        .isInstanceOf(DeliveryBusinessException.class)
        .extracting(exception -> ((DeliveryBusinessException) exception).getErrorCode())
        .isEqualTo(DeliveryErrorCode.INVALID_STATUS_TRANSITION);
    assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.HUB_WAITING);
  }

  @Test
  void modifyReturnsTheDeliveryWithItsRoutes() {
    Delivery delivery = existingDelivery();
    DeliveryRoute first = route(delivery, 1);
    DeliveryRoute second = route(delivery, 2);
    given(deliveryRepository.findRoutesByDeliveryId(DELIVERY_ID))
        .willReturn(List.of(first, second));

    DeliveryResult result = service.modify(DELIVERY_ID, DeliveryStatus.HUB_MOVING, null);

    assertThat(result.routes())
        .containsExactly(DeliveryRouteResult.from(first), DeliveryRouteResult.from(second));
  }

  @Test
  void modifyOfAnUnknownDeliveryFailsWithDeliveryNotFoundWithoutReadingRoutes() {
    given(deliveryRepository.findById(DELIVERY_ID)).willReturn(Optional.empty());

    assertThatThrownBy(() -> service.modify(DELIVERY_ID, DeliveryStatus.HUB_MOVING, ASSIGNEE_ID))
        .isInstanceOf(DeliveryBusinessException.class)
        .extracting(exception -> ((DeliveryBusinessException) exception).getErrorCode())
        .isEqualTo(DeliveryErrorCode.DELIVERY_NOT_FOUND);
    then(deliveryRepository).should(never()).findRoutesByDeliveryId(any());
  }

  private Delivery existingDelivery() {
    Delivery delivery =
        Delivery.create(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "서울특별시 송파구 송파대로 55", "홍길동");
    given(deliveryRepository.findById(DELIVERY_ID)).willReturn(Optional.of(delivery));
    given(deliveryRepository.findRoutesByDeliveryId(DELIVERY_ID)).willReturn(List.of());
    return delivery;
  }

  private static DeliveryRoute route(Delivery delivery, int sequence) {
    return new DeliveryRoute(
        UUID.randomUUID(),
        delivery,
        sequence,
        UUID.randomUUID(),
        UUID.randomUUID(),
        new BigDecimal("120.5"),
        5400L,
        DeliveryRouteStatus.WAITING,
        null);
  }
}
