package com.notfound.logistics.delivery.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.notfound.logistics.common.api.ApiResponse;
import com.notfound.logistics.delivery.application.dto.DeliveryCreateCommand;
import com.notfound.logistics.delivery.application.dto.DeliveryCreationResult;
import com.notfound.logistics.delivery.domain.exception.DeliveryBusinessException;
import com.notfound.logistics.delivery.domain.exception.DeliveryErrorCode;
import com.notfound.logistics.delivery.domain.model.Delivery;
import com.notfound.logistics.delivery.domain.model.DeliveryStatus;
import com.notfound.logistics.delivery.domain.repository.DeliveryRepository;
import com.notfound.logistics.delivery.infrastructure.client.HubClient;
import com.notfound.logistics.delivery.infrastructure.client.HubClient.Hub;
import com.notfound.logistics.delivery.infrastructure.client.HubClient.HubRoute;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 저장 중 order_id 유니크 인덱스 위반(동시 요청)이 났을 때의 판단을 확인한다. */
class DeliveryCreationServiceTest {
  private static final UUID ORDER_ID = UUID.randomUUID();
  private static final UUID DEPARTURE = UUID.randomUUID();
  private static final UUID DESTINATION = UUID.randomUUID();
  private static final DeliveryCreateCommand COMMAND =
      new DeliveryCreateCommand(ORDER_ID, DEPARTURE, DESTINATION, "부산시 해운대구 1", "홍길동");

  private final DeliveryRepository deliveryRepository = mock(DeliveryRepository.class);
  private final HubClient hubClient = mock(HubClient.class);
  private final DeliveryCreationService service =
      new DeliveryCreationService(
          deliveryRepository,
          hubClient,
          new TransactionTemplate(mock(PlatformTransactionManager.class)));

  @BeforeEach
  void hubResponds() {
    given(hubClient.getHub(any()))
        .willAnswer(
            invocation -> new ApiResponse<>(new Hub(invocation.getArgument(0), "허브", "주소")));
    given(hubClient.getRoute(DEPARTURE, DESTINATION))
        .willReturn(
            new ApiResponse<>(
                new HubRoute(UUID.randomUUID(), DEPARTURE, DESTINATION, 60, BigDecimal.TEN)));
    given(deliveryRepository.save(any()))
        .willThrow(new DataIntegrityViolationException("uk_p_deliveries_order_id"));
  }

  @Test
  void uniqueViolationWithSameContentReturnsTheDeliverySavedFirstAsNotCreated() {
    Delivery savedFirst = existing("부산시 해운대구 1");
    given(deliveryRepository.findByOrderId(ORDER_ID))
        .willReturn(Optional.empty(), Optional.of(savedFirst));
    given(deliveryRepository.findRoutesByDeliveryId(savedFirst.getId())).willReturn(List.of());

    DeliveryCreationResult result = service.create(COMMAND);

    assertThat(result.created()).isFalse();
    assertThat(result.delivery().deliveryId()).isEqualTo(savedFirst.getId());
  }

  @Test
  void uniqueViolationWithDifferentContentIsConflict() {
    // 목 생성(내부 스텁)을 다른 스텁 안에서 하지 않도록 먼저 만든다.
    Delivery savedFirst = existing("부산시 수영구 2");
    given(deliveryRepository.findByOrderId(ORDER_ID))
        .willReturn(Optional.empty(), Optional.of(savedFirst));

    assertThatThrownBy(() -> service.create(COMMAND))
        .isInstanceOfSatisfying(
            DeliveryBusinessException.class,
            exception ->
                assertThat(exception.getErrorCode())
                    .isEqualTo(DeliveryErrorCode.DELIVERY_ALREADY_EXISTS));
  }

  @Test
  void uniqueViolationWithoutAnExistingDeliveryIsRethrown() {
    // 다른 제약 위반이라 기존 배송이 없으면 원래 예외를 그대로 던진다.
    given(deliveryRepository.findByOrderId(ORDER_ID)).willReturn(Optional.empty());

    assertThatThrownBy(() -> service.create(COMMAND))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private static Delivery existing(String address) {
    Delivery delivery = mock(Delivery.class);
    given(delivery.getId()).willReturn(UUID.randomUUID());
    given(delivery.getOrderId()).willReturn(ORDER_ID);
    given(delivery.getStatus()).willReturn(DeliveryStatus.HUB_WAITING);
    given(delivery.getDepartureHubId()).willReturn(DEPARTURE);
    given(delivery.getDestinationHubId()).willReturn(DESTINATION);
    given(delivery.getAddress()).willReturn(address);
    given(delivery.getRecipientName()).willReturn("홍길동");
    return delivery;
  }
}
