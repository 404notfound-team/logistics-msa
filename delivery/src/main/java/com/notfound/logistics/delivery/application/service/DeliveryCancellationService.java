package com.notfound.logistics.delivery.application.service;

import com.notfound.logistics.delivery.application.dto.DeliveryResult;
import com.notfound.logistics.delivery.domain.exception.DeliveryBusinessException;
import com.notfound.logistics.delivery.domain.exception.DeliveryErrorCode;
import com.notfound.logistics.delivery.domain.model.Delivery;
import com.notfound.logistics.delivery.domain.model.DeliveryRoute;
import com.notfound.logistics.delivery.domain.repository.DeliveryRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class DeliveryCancellationService {
  private final DeliveryRepository deliveryRepository;

  /**
   * 배송과 배송의 모든 경로를 취소한다. 이미 취소된 배송의 재요청은 현재 배송을 그대로 반환한다.
   *
   * @throws DeliveryBusinessException 배송이 없으면 DELIVERY_NOT_FOUND, 이미 출발했으면 DELIVERY_ALREADY_STARTED
   */
  public DeliveryResult cancel(UUID deliveryId) {
    Delivery delivery =
        deliveryRepository
            .findByIdForUpdate(deliveryId)
            .orElseThrow(() -> new DeliveryBusinessException(DeliveryErrorCode.DELIVERY_NOT_FOUND));
    delivery.cancel();
    List<DeliveryRoute> routes = deliveryRepository.findRoutesByDeliveryId(deliveryId);
    routes.forEach(DeliveryRoute::cancel);
    return DeliveryResult.of(delivery, routes);
  }
}
