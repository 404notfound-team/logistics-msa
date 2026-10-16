package com.notfound.logistics.delivery.application.service;

import com.notfound.logistics.delivery.application.dto.DeliveryResult;
import com.notfound.logistics.delivery.domain.exception.DeliveryBusinessException;
import com.notfound.logistics.delivery.domain.exception.DeliveryErrorCode;
import com.notfound.logistics.delivery.domain.model.Delivery;
import com.notfound.logistics.delivery.domain.model.DeliveryRoute;
import com.notfound.logistics.delivery.domain.repository.DeliveryRepository;
import com.notfound.logistics.delivery.domain.repository.DeliverySearchCondition;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DeliveryQueryService {
  private final DeliveryRepository deliveryRepository;

  public Page<DeliveryResult> search(DeliverySearchCondition condition, Pageable pageable) {
    Page<Delivery> deliveries = deliveryRepository.search(condition, pageable);
    Map<UUID, List<DeliveryRoute>> routesByDeliveryId = routesOf(deliveries.getContent());
    return deliveries.map(
        delivery ->
            DeliveryResult.of(
                delivery, routesByDeliveryId.getOrDefault(delivery.getId(), List.of())));
  }

  public DeliveryResult searchDetail(UUID deliveryId) {
    Delivery delivery =
        deliveryRepository
            .findById(deliveryId)
            .orElseThrow(() -> new DeliveryBusinessException(DeliveryErrorCode.DELIVERY_NOT_FOUND));
    return DeliveryResult.of(delivery, deliveryRepository.findRoutesByDeliveryId(deliveryId));
  }

  // 페이지의 배송 구간을 한 번에 읽어 배송별로 묶는다. 구간 순서(sequence)는 쿼리 순서를 따른다.
  private Map<UUID, List<DeliveryRoute>> routesOf(List<Delivery> deliveries) {
    if (deliveries.isEmpty()) {
      return Map.of();
    }
    List<UUID> deliveryIds = deliveries.stream().map(Delivery::getId).toList();
    return deliveryRepository.findRoutesByDeliveryIds(deliveryIds).stream()
        .collect(Collectors.groupingBy(route -> route.getDelivery().getId()));
  }
}
