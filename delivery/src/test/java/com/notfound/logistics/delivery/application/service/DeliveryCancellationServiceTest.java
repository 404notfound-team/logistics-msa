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
import org.springframework.test.util.ReflectionTestUtils;

class DeliveryCancellationServiceTest {
  private final DeliveryRepository deliveryRepository = mock(DeliveryRepository.class);
  private final DeliveryCancellationService service =
      new DeliveryCancellationService(deliveryRepository);

  @Test
  void cancelsTheWaitingDeliveryAndAllOfItsRoutes() {
    UUID deliveryId = UUID.randomUUID();
    Delivery delivery = delivery(deliveryId, DeliveryStatus.HUB_WAITING);
    DeliveryRoute first = route(delivery, 1);
    DeliveryRoute second = route(delivery, 2);
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
  void unknownDeliveryIsNotFound() {
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
  void deliveryIsReadWithAWriteLock() {
    UUID deliveryId = UUID.randomUUID();
    given(deliveryRepository.findByIdForUpdate(deliveryId))
        .willReturn(Optional.of(delivery(deliveryId, DeliveryStatus.HUB_WAITING)));
    given(deliveryRepository.findRoutesByDeliveryId(deliveryId)).willReturn(List.of());

    service.cancel(deliveryId);

    then(deliveryRepository).should().findByIdForUpdate(deliveryId);
    then(deliveryRepository).should(never()).findById(any());
  }

  private static Delivery delivery(UUID id, DeliveryStatus status) {
    Delivery delivery =
        Delivery.create(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "부산시 해운대구 1", "홍길동");
    ReflectionTestUtils.setField(delivery, "id", id);
    ReflectionTestUtils.setField(delivery, "status", status);
    return delivery;
  }

  private static DeliveryRoute route(Delivery delivery, int sequence) {
    return new DeliveryRoute(
        UUID.randomUUID(),
        delivery,
        sequence,
        UUID.randomUUID(),
        UUID.randomUUID(),
        BigDecimal.TEN,
        600L,
        DeliveryRouteStatus.WAITING,
        null);
  }
}
