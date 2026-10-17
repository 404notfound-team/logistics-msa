package com.notfound.logistics.delivery.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import com.notfound.logistics.delivery.application.dto.DeliveryActor;
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
import java.time.Instant;
import org.springframework.test.util.ReflectionTestUtils;

class DeliveryCommandServiceTest {
  private static final String ACTOR = "orders-user";
  private static final UUID DELIVERY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
  private static final UUID ASSIGNEE_ID = UUID.fromString("00000000-0000-0000-0000-000000000007");
  private static final UUID OTHER_ASSIGNEE_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000008");
  private static final DeliveryActor MASTER =
      new DeliveryActor(UUID.fromString("00000000-0000-0000-0000-000000000009"), true);
  private static final DeliveryActor ASSIGNEE = new DeliveryActor(ASSIGNEE_ID, false);
  private static final DeliveryActor OTHER_USER = new DeliveryActor(OTHER_ASSIGNEE_ID, false);

  private final DeliveryRepository deliveryRepository = mock(DeliveryRepository.class);
  private final DeliveryCommandService service = new DeliveryCommandService(deliveryRepository, () -> Optional.of(ACTOR));

  @Test
  void modifyChangesOnlyTheStatusWhenNoAssigneeIsGiven() {
    Delivery delivery = existingDelivery();
    delivery.assign(ASSIGNEE_ID);

    DeliveryResult result = service.modify(DELIVERY_ID, DeliveryStatus.HUB_MOVING, null, MASTER);

    assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.HUB_MOVING);
    assertThat(delivery.getAssigneeId()).isEqualTo(ASSIGNEE_ID);
    assertThat(result.status()).isEqualTo(DeliveryStatus.HUB_MOVING);
    assertThat(result.assigneeId()).isEqualTo(ASSIGNEE_ID);
  }

  @Test
  void modifyReassignsAHubWaitingDeliveryWithoutChangingItsStatus() {
    Delivery delivery = existingDelivery();
    delivery.assign(ASSIGNEE_ID);

    DeliveryResult result = service.modify(DELIVERY_ID, null, OTHER_ASSIGNEE_ID, MASTER);

    assertThat(delivery.getAssigneeId()).isEqualTo(OTHER_ASSIGNEE_ID);
    assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.HUB_WAITING);
    assertThat(result.assigneeId()).isEqualTo(OTHER_ASSIGNEE_ID);
  }

  @Test
  void assignmentIsJudgedByTheCurrentStatusSoAssigningAndLeavingHubWaitingTogetherIsAllowed() {
    Delivery delivery = existingDelivery();

    DeliveryResult result =
        service.modify(DELIVERY_ID, DeliveryStatus.HUB_MOVING, ASSIGNEE_ID, MASTER);

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
            () ->
                service.modify(DELIVERY_ID, DeliveryStatus.HUB_WAITING, OTHER_ASSIGNEE_ID, MASTER))
        .isInstanceOf(DeliveryBusinessException.class)
        .extracting(exception -> ((DeliveryBusinessException) exception).getErrorCode())
        .isEqualTo(DeliveryErrorCode.ASSIGNMENT_NOT_ALLOWED);
    assertThat(delivery.getAssigneeId()).isEqualTo(ASSIGNEE_ID);
    assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.HUB_MOVING);
  }

  @Test
  void skippingAStatusFailsWithInvalidStatusTransitionAndChangesNothing() {
    Delivery delivery = existingDelivery();

    assertThatThrownBy(() -> service.modify(DELIVERY_ID, DeliveryStatus.DELIVERED, null, MASTER))
        .isInstanceOf(DeliveryBusinessException.class)
        .extracting(exception -> ((DeliveryBusinessException) exception).getErrorCode())
        .isEqualTo(DeliveryErrorCode.INVALID_STATUS_TRANSITION);
    assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.HUB_WAITING);
  }

  @Test
  void theAssigneeCanChangeTheStatusOfTheirDelivery() {
    Delivery delivery = existingDelivery();
    delivery.assign(ASSIGNEE_ID);

    DeliveryResult result = service.modify(DELIVERY_ID, DeliveryStatus.HUB_MOVING, null, ASSIGNEE);

    assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.HUB_MOVING);
    assertThat(result.status()).isEqualTo(DeliveryStatus.HUB_MOVING);
  }

  @Test
  void aUserWhoIsNotTheAssigneeCannotChangeTheStatus() {
    Delivery delivery = existingDelivery();
    delivery.assign(ASSIGNEE_ID);

    assertThatThrownBy(
            () -> service.modify(DELIVERY_ID, DeliveryStatus.HUB_MOVING, null, OTHER_USER))
        .isInstanceOf(DeliveryBusinessException.class)
        .extracting(exception -> ((DeliveryBusinessException) exception).getErrorCode())
        .isEqualTo(DeliveryErrorCode.STATUS_CHANGE_FORBIDDEN);
    assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.HUB_WAITING);
  }

  @Test
  void aUserCannotChangeTheStatusOfAnUnassignedDelivery() {
    Delivery delivery = existingDelivery();

    assertThatThrownBy(
            () -> service.modify(DELIVERY_ID, DeliveryStatus.HUB_MOVING, null, OTHER_USER))
        .isInstanceOf(DeliveryBusinessException.class)
        .extracting(exception -> ((DeliveryBusinessException) exception).getErrorCode())
        .isEqualTo(DeliveryErrorCode.STATUS_CHANGE_FORBIDDEN);
    assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.HUB_WAITING);
  }

  @Test
  void onlyMasterCanAssignSoEvenTheAssigneeCannotReassignWithoutReadingTheDelivery() {
    Delivery delivery = existingDelivery();
    delivery.assign(ASSIGNEE_ID);

    assertThatThrownBy(
            () ->
                service.modify(DELIVERY_ID, DeliveryStatus.HUB_MOVING, OTHER_ASSIGNEE_ID, ASSIGNEE))
        .isInstanceOf(DeliveryBusinessException.class)
        .extracting(exception -> ((DeliveryBusinessException) exception).getErrorCode())
        .isEqualTo(DeliveryErrorCode.ASSIGNMENT_FORBIDDEN);
    assertThat(delivery.getAssigneeId()).isEqualTo(ASSIGNEE_ID);
    assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.HUB_WAITING);
    then(deliveryRepository).should(never()).findById(any());
  }

  @Test
  void modifyReturnsTheDeliveryWithItsRoutes() {
    Delivery delivery = existingDelivery();
    DeliveryRoute first = route(delivery, 1);
    DeliveryRoute second = route(delivery, 2);
    given(deliveryRepository.findRoutesByDeliveryId(DELIVERY_ID))
        .willReturn(List.of(first, second));

    DeliveryResult result = service.modify(DELIVERY_ID, DeliveryStatus.HUB_MOVING, null, MASTER);

    assertThat(result.routes())
        .containsExactly(DeliveryRouteResult.from(first), DeliveryRouteResult.from(second));
  }

  @Test
  void modifyOfAnUnknownDeliveryFailsWithDeliveryNotFoundWithoutReadingRoutes() {
    given(deliveryRepository.findById(DELIVERY_ID)).willReturn(Optional.empty());

    assertThatThrownBy(
            () -> service.modify(DELIVERY_ID, DeliveryStatus.HUB_MOVING, ASSIGNEE_ID, MASTER))
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
  // ---------------------------------------------------------------------------------------------
  // cancel: [내부 API] POST /internal/deliveries/{id}/cancel
  // ---------------------------------------------------------------------------------------------

  @Test
  void cancelsTheWaitingDeliveryAndAllOfItsRoutes() {
    UUID deliveryId = UUID.randomUUID();
    Delivery delivery = delivery(deliveryId, DeliveryStatus.HUB_WAITING);
    DeliveryRoute first = route(delivery, 1, DeliveryRouteStatus.WAITING);
    DeliveryRoute second = route(delivery, 2, DeliveryRouteStatus.WAITING);
    given(deliveryRepository.findByIdForUpdate(deliveryId)).willReturn(Optional.of(delivery));
    given(deliveryRepository.findRoutesByDeliveryId(deliveryId)).willReturn(List.of(first, second));

    DeliveryResult result = service.cancel(deliveryId);

    assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.CANCELLED);
    assertThat(first.getStatus()).isEqualTo(DeliveryRouteStatus.CANCELLED);
    assertThat(second.getStatus()).isEqualTo(DeliveryRouteStatus.CANCELLED);
    assertThat(result.deliveryId()).isEqualTo(deliveryId);
    assertThat(result.orderId()).isEqualTo(delivery.getOrderId());
    assertThat(result.status()).isEqualTo(DeliveryStatus.CANCELLED);
    assertThat(result.routes())
        .extracting(DeliveryRouteResult::status)
        .containsExactly(DeliveryRouteStatus.CANCELLED, DeliveryRouteStatus.CANCELLED);
  }

  @Test
  void cancelledDeliveryIsReturnedAsItIs() {
    UUID deliveryId = UUID.randomUUID();
    Delivery delivery = delivery(deliveryId, DeliveryStatus.CANCELLED);
    given(deliveryRepository.findByIdForUpdate(deliveryId)).willReturn(Optional.of(delivery));
    given(deliveryRepository.findRoutesByDeliveryId(deliveryId)).willReturn(List.of());

    DeliveryResult result = service.cancel(deliveryId);

    assertThat(result.deliveryId()).isEqualTo(deliveryId);
    assertThat(result.status()).isEqualTo(DeliveryStatus.CANCELLED);
  }

  @Test
  void startedDeliveryIsRejectedWithoutTouchingItsRoutes() {
    UUID deliveryId = UUID.randomUUID();
    Delivery delivery = delivery(deliveryId, DeliveryStatus.HUB_MOVING);
    given(deliveryRepository.findByIdForUpdate(deliveryId)).willReturn(Optional.of(delivery));

    assertThatThrownBy(() -> service.cancel(deliveryId))
        .isInstanceOfSatisfying(
            DeliveryBusinessException.class,
            exception ->
                assertThat(exception.getErrorCode())
                    .isEqualTo(DeliveryErrorCode.DELIVERY_ALREADY_STARTED));
    assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.HUB_MOVING);
    then(deliveryRepository).should(never()).findRoutesByDeliveryId(any());
  }

  @Test
  void cancelOfAnUnknownDeliveryIsNotFound() {
    UUID deliveryId = UUID.randomUUID();
    given(deliveryRepository.findByIdForUpdate(deliveryId)).willReturn(Optional.empty());

    assertThatThrownBy(() -> service.cancel(deliveryId))
        .isInstanceOfSatisfying(
            DeliveryBusinessException.class,
            exception ->
                assertThat(exception.getErrorCode())
                    .isEqualTo(DeliveryErrorCode.DELIVERY_NOT_FOUND));
    then(deliveryRepository).should(never()).findRoutesByDeliveryId(any());
  }

  @Test
  void cancelReadsTheDeliveryWithAWriteLock() {
    UUID deliveryId = UUID.randomUUID();
    given(deliveryRepository.findByIdForUpdate(deliveryId))
        .willReturn(Optional.of(delivery(deliveryId, DeliveryStatus.HUB_WAITING)));
    given(deliveryRepository.findRoutesByDeliveryId(deliveryId)).willReturn(List.of());

    service.cancel(deliveryId);

    then(deliveryRepository).should().findByIdForUpdate(deliveryId);
    then(deliveryRepository).should(never()).findById(any());
  }

  // ---------------------------------------------------------------------------------------------
  // delete: [내부 API] DELETE /internal/deliveries/{id}
  // ---------------------------------------------------------------------------------------------

  @Test
  void deletesTheFinishedDeliveryAndAllOfItsRoutesWithTheSameActorAndTime() {
    UUID deliveryId = UUID.randomUUID();
    Delivery delivery = delivery(deliveryId, DeliveryStatus.DELIVERED);
    DeliveryRoute first = route(delivery, 1, DeliveryRouteStatus.ARRIVED);
    DeliveryRoute second = route(delivery, 2, DeliveryRouteStatus.ARRIVED);
    given(deliveryRepository.findIncludingDeletedForUpdate(deliveryId))
        .willReturn(Optional.of(delivery));
    given(deliveryRepository.findRoutesByDeliveryId(deliveryId)).willReturn(List.of(first, second));
    Instant before = Instant.now();

    service.delete(deliveryId);

    assertThat(delivery.getDeletedBy()).isEqualTo(ACTOR);
    assertThat(delivery.getDeletedAt()).isBetween(before, Instant.now());
    assertThat(List.of(first, second))
        .allSatisfy(
            route -> {
              assertThat(route.getDeletedBy()).isEqualTo(ACTOR);
              assertThat(route.getDeletedAt()).isEqualTo(delivery.getDeletedAt());
            });
  }

  @Test
  void deletedDeliveryIsLeftAsItIsWithoutReadingRoutes() {
    UUID deliveryId = UUID.randomUUID();
    Delivery delivery = delivery(deliveryId, DeliveryStatus.DELIVERED);
    Instant firstDeletion = Instant.parse("2026-01-01T00:00:00Z");
    delivery.delete("first-user", firstDeletion);
    given(deliveryRepository.findIncludingDeletedForUpdate(deliveryId))
        .willReturn(Optional.of(delivery));

    service.delete(deliveryId);

    assertThat(delivery.getDeletedAt()).isEqualTo(firstDeletion);
    assertThat(delivery.getDeletedBy()).isEqualTo("first-user");
    then(deliveryRepository).should(never()).findRoutesByDeliveryId(any());
  }

  @Test
  void deliveryInProgressIsRejectedWithoutDeletingItsRoutes() {
    UUID deliveryId = UUID.randomUUID();
    Delivery delivery = delivery(deliveryId, DeliveryStatus.HUB_MOVING);
    DeliveryRoute route = route(delivery, 1, DeliveryRouteStatus.IN_TRANSIT);
    given(deliveryRepository.findIncludingDeletedForUpdate(deliveryId))
        .willReturn(Optional.of(delivery));
    given(deliveryRepository.findRoutesByDeliveryId(deliveryId)).willReturn(List.of(route));

    assertThatThrownBy(() -> service.delete(deliveryId))
        .isInstanceOfSatisfying(
            DeliveryBusinessException.class,
            exception ->
                assertThat(exception.getErrorCode())
                    .isEqualTo(DeliveryErrorCode.DELIVERY_IN_PROGRESS));
    assertThat(delivery.getDeletedAt()).isNull();
    assertThat(route.getDeletedAt()).isNull();
  }

  @Test
  void routesAreReadBeforeTheDeliveryIsMarkedDeleted() {
    // 경로 조회는 삭제되지 않은 배송만 거치므로, 배송에 삭제 표시를 하기 전에 읽어야 한다.
    UUID deliveryId = UUID.randomUUID();
    Delivery delivery = delivery(deliveryId, DeliveryStatus.DELIVERED);
    given(deliveryRepository.findIncludingDeletedForUpdate(deliveryId))
        .willReturn(Optional.of(delivery));
    given(deliveryRepository.findRoutesByDeliveryId(deliveryId))
        .willAnswer(
            invocation -> {
              assertThat(delivery.getDeletedAt()).isNull();
              return List.of();
            });

    service.delete(deliveryId);

    then(deliveryRepository).should().findRoutesByDeliveryId(deliveryId);
    assertThat(delivery.getDeletedAt()).isNotNull();
  }

  @Test
  void deleteOfAnUnknownDeliveryIsNotFound() {
    UUID deliveryId = UUID.randomUUID();
    given(deliveryRepository.findIncludingDeletedForUpdate(deliveryId))
        .willReturn(Optional.empty());

    assertThatThrownBy(() -> service.delete(deliveryId))
        .isInstanceOfSatisfying(
            DeliveryBusinessException.class,
            exception ->
                assertThat(exception.getErrorCode())
                    .isEqualTo(DeliveryErrorCode.DELIVERY_NOT_FOUND));
    then(deliveryRepository).should(never()).findRoutesByDeliveryId(any());
  }

  @Test
  void deleteReadsTheDeliveryIncludingDeletedOnesWithAWriteLock() {
    UUID deliveryId = UUID.randomUUID();
    given(deliveryRepository.findIncludingDeletedForUpdate(deliveryId))
        .willReturn(Optional.of(delivery(deliveryId, DeliveryStatus.CANCELLED)));
    given(deliveryRepository.findRoutesByDeliveryId(deliveryId)).willReturn(List.of());

    service.delete(deliveryId);

    then(deliveryRepository).should().findIncludingDeletedForUpdate(deliveryId);
    then(deliveryRepository).should(never()).findById(any());
    then(deliveryRepository).should(never()).findByIdForUpdate(any());
  }

  private static Delivery delivery(UUID id, DeliveryStatus status) {
    Delivery delivery =
        Delivery.create(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "부산시 해운대구 1", "홍길동");
    ReflectionTestUtils.setField(delivery, "id", id);
    ReflectionTestUtils.setField(delivery, "status", status);
    return delivery;
  }

  private static DeliveryRoute route(Delivery delivery, int sequence, DeliveryRouteStatus status) {
    return new DeliveryRoute(
        UUID.randomUUID(),
        delivery,
        sequence,
        UUID.randomUUID(),
        UUID.randomUUID(),
        BigDecimal.TEN,
        600L,
        status,
        null);
  }
}
