package com.notfound.logistics.delivery.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.notfound.logistics.delivery.application.dto.DeliveryDetailResult;
import com.notfound.logistics.delivery.application.dto.DeliveryRouteResult;
import com.notfound.logistics.delivery.domain.exception.DeliveryBusinessException;
import com.notfound.logistics.delivery.domain.exception.DeliveryErrorCode;
import com.notfound.logistics.delivery.domain.model.Delivery;
import com.notfound.logistics.delivery.domain.model.DeliveryRoute;
import com.notfound.logistics.delivery.domain.model.DeliveryStatus;
import com.notfound.logistics.delivery.domain.repository.DeliveryRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeliveryQueryServiceTest {
  private final DeliveryRepository deliveryRepository = mock(DeliveryRepository.class);
  private final DeliveryQueryService service = new DeliveryQueryService(deliveryRepository);

  @Test
  void searchDetailReturnsTheDeliveryWithItsRoutesAsAResult() {
    Delivery delivery = mock(Delivery.class);
    DeliveryRoute route =
        new DeliveryRoute(
            UUID.randomUUID(),
            delivery,
            1,
            UUID.randomUUID(),
            UUID.randomUUID(),
            120,
            5400,
            DeliveryStatus.HUB_WAITING,
            null);
    UUID deliveryId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    given(delivery.getId()).willReturn(deliveryId);
    given(delivery.getOrderId()).willReturn(orderId);
    given(delivery.getStatus()).willReturn(DeliveryStatus.HUB_WAITING);
    given(delivery.getRecipientName()).willReturn("홍길동");
    given(deliveryRepository.findById(deliveryId)).willReturn(Optional.of(delivery));
    given(deliveryRepository.findRoutesByDeliveryId(deliveryId)).willReturn(List.of(route));

    DeliveryDetailResult result = service.searchDetail(deliveryId);

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

    assertThatThrownBy(() -> service.searchDetail(deliveryId))
        .isInstanceOf(DeliveryBusinessException.class)
        .extracting(exception -> ((DeliveryBusinessException) exception).getErrorCode())
        .isEqualTo(DeliveryErrorCode.DELIVERY_NOT_FOUND);
  }
}
