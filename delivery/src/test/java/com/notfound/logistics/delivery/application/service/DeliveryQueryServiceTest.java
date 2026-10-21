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
import com.notfound.logistics.delivery.domain.repository.DeliverySearchCondition;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

class DeliveryQueryServiceTest {
  private static final DeliveryActor VIEWER = new DeliveryActor(UUID.randomUUID(), false, false);
  private static final DeliverySearchCondition NO_CONDITION =
      new DeliverySearchCondition(null, null, null, null, null, null, null);

  private final DeliveryRepository deliveryRepository = mock(DeliveryRepository.class);
  private final DeliveryQueryService service = new DeliveryQueryService(deliveryRepository);

  @Test
  void searchReadsTheRoutesOfThePageInOneQueryAndGroupsThemByDelivery() {
    Delivery withRoutes = delivery(UUID.randomUUID());
    Delivery withoutRoutes = delivery(UUID.randomUUID());
    DeliveryRoute first = route(withRoutes, 1);
    DeliveryRoute second = route(withRoutes, 2);
    Pageable pageable = PageRequest.of(0, 20);
    given(deliveryRepository.search(NO_CONDITION, pageable))
        .willReturn(new PageImpl<>(List.of(withRoutes, withoutRoutes), pageable, 2));
    given(
            deliveryRepository.findRoutesByDeliveryIds(
                List.of(withRoutes.getId(), withoutRoutes.getId())))
        .willReturn(List.of(first, second));

    Page<DeliveryResult> result = service.search(NO_CONDITION, pageable, VIEWER);

    assertThat(result.getContent()).hasSize(2);
    assertThat(result.getContent().get(0).routes())
        .containsExactly(DeliveryRouteResult.from(first), DeliveryRouteResult.from(second));
    assertThat(result.getContent().get(1).routes()).isEmpty();
    assertThat(result.getTotalElements()).isEqualTo(2);
  }

  @Test
  void searchOfAnEmptyPageDoesNotReadRoutes() {
    Pageable pageable = PageRequest.of(0, 20);
    given(deliveryRepository.search(NO_CONDITION, pageable)).willReturn(Page.empty(pageable));

    assertThat(service.search(NO_CONDITION, pageable, VIEWER).getContent()).isEmpty();
    then(deliveryRepository).should(never()).findRoutesByDeliveryIds(any());
  }

  @Test
  void searchDetailReturnsTheDeliveryWithItsRoutesAsAResult() {
    UUID deliveryId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    Delivery delivery = delivery(deliveryId);
    given(delivery.getOrderId()).willReturn(orderId);
    given(delivery.getStatus()).willReturn(DeliveryStatus.HUB_WAITING);
    given(delivery.getRecipientName()).willReturn("홍길동");
    DeliveryRoute route = route(delivery, 1);
    given(deliveryRepository.findById(deliveryId)).willReturn(Optional.of(delivery));
    given(deliveryRepository.findRoutesByDeliveryId(deliveryId)).willReturn(List.of(route));

    DeliveryResult result = service.searchDetail(deliveryId, VIEWER);

    assertThat(result.deliveryId()).isEqualTo(deliveryId);
    assertThat(result.orderId()).isEqualTo(orderId);
    assertThat(result.status()).isEqualTo(DeliveryStatus.HUB_WAITING);
    assertThat(result.recipientName()).isEqualTo("홍길동");
    assertThat(result.routes()).containsExactly(DeliveryRouteResult.from(route));
  }

  @Test
  void searchDetailOfAnUnknownDeliveryFailsWithDeliveryNotFound() {
    UUID deliveryId = UUID.randomUUID();
    given(deliveryRepository.findById(deliveryId)).willReturn(Optional.empty());

    assertThatThrownBy(() -> service.searchDetail(deliveryId, VIEWER))
        .isInstanceOf(DeliveryBusinessException.class)
        .extracting(exception -> ((DeliveryBusinessException) exception).getErrorCode())
        .isEqualTo(DeliveryErrorCode.DELIVERY_NOT_FOUND);
  }

  @Test
  void searchRoutesReturnsTheRoutesOfAnExistingDeliveryInSequenceOrder() {
    UUID deliveryId = UUID.randomUUID();
    Delivery delivery = delivery(deliveryId);
    DeliveryRoute first = route(delivery, 1);
    DeliveryRoute second = route(delivery, 2);
    given(deliveryRepository.findById(deliveryId)).willReturn(Optional.of(delivery));
    given(deliveryRepository.findRoutesByDeliveryId(deliveryId)).willReturn(List.of(first, second));

    assertThat(service.searchRoutes(deliveryId, VIEWER))
        .containsExactly(DeliveryRouteResult.from(first), DeliveryRouteResult.from(second));
  }

  @Test
  void searchRoutesOfAnUnknownDeliveryFailsWithDeliveryNotFoundWithoutReadingRoutes() {
    UUID deliveryId = UUID.randomUUID();
    given(deliveryRepository.findById(deliveryId)).willReturn(Optional.empty());

    assertThatThrownBy(() -> service.searchRoutes(deliveryId, VIEWER))
        .isInstanceOf(DeliveryBusinessException.class)
        .extracting(exception -> ((DeliveryBusinessException) exception).getErrorCode())
        .isEqualTo(DeliveryErrorCode.DELIVERY_NOT_FOUND);
    then(deliveryRepository).should(never()).findRoutesByDeliveryId(any());
  }

  @Test
  void searchByOrderIdReturnsTheDeliveryOfTheOrderWithItsRoutes() {
    UUID deliveryId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    Delivery delivery = delivery(deliveryId);
    given(delivery.getOrderId()).willReturn(orderId);
    given(delivery.getStatus()).willReturn(DeliveryStatus.HUB_MOVING);
    DeliveryRoute route = route(delivery, 1);
    given(deliveryRepository.findByOrderId(orderId)).willReturn(Optional.of(delivery));
    given(deliveryRepository.findRoutesByDeliveryId(deliveryId)).willReturn(List.of(route));

    DeliveryResult result = service.searchByOrderId(orderId);

    assertThat(result.deliveryId()).isEqualTo(deliveryId);
    assertThat(result.orderId()).isEqualTo(orderId);
    assertThat(result.status()).isEqualTo(DeliveryStatus.HUB_MOVING);
    assertThat(result.routes()).containsExactly(DeliveryRouteResult.from(route));
  }

  @Test
  void searchByOrderIdWithoutADeliveryFailsWithDeliveryNotFoundWithoutReadingRoutes() {
    UUID orderId = UUID.randomUUID();
    given(deliveryRepository.findByOrderId(orderId)).willReturn(Optional.empty());

    assertThatThrownBy(() -> service.searchByOrderId(orderId))
        .isInstanceOf(DeliveryBusinessException.class)
        .extracting(exception -> ((DeliveryBusinessException) exception).getErrorCode())
        .isEqualTo(DeliveryErrorCode.DELIVERY_NOT_FOUND);
    then(deliveryRepository).should(never()).findRoutesByDeliveryId(any());
  }

  private static Delivery delivery(UUID id) {
    Delivery delivery = mock(Delivery.class);
    given(delivery.getId()).willReturn(id);
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
