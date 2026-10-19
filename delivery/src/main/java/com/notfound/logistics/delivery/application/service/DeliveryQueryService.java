package com.notfound.logistics.delivery.application.service;

import com.notfound.logistics.delivery.application.dto.DeliveryActor;
import com.notfound.logistics.delivery.application.dto.DeliveryResult;
import com.notfound.logistics.delivery.application.dto.DeliveryRouteResult;
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

/**
 * 배송과 배송의 경로를 조회한다. 모든 메서드는 읽기 전용 트랜잭션에서 실행되며 삭제된 배송·경로는 조회되지 않는다.
 *
 * <p>배송 생성은 DeliveryCreationService가, 취소·삭제 같은 변경은 DeliveryCommandService가 맡는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DeliveryQueryService {
  private final DeliveryRepository deliveryRepository;

  // ---------------------------------------------------------------------------------------------
  // 외부 API (Gateway → Delivery). DeliveryController에서 호출한다.
  // ---------------------------------------------------------------------------------------------

  /** [외부 API] GET /api/deliveries. 조건에 맞는 배송을 페이지로 읽고, 각 배송의 경로를 함께 담는다. */
  public Page<DeliveryResult> search(
      DeliverySearchCondition condition, Pageable pageable, DeliveryActor actor) {
    if (actor.assignedDeliveriesOnly()) {
      if (condition.assigneeId() != null && !actor.userId().equals(condition.assigneeId())) {
        return Page.empty(pageable);
      }
      condition =
          new DeliverySearchCondition(
              condition.orderId(),
              condition.status(),
              condition.departureHubId(),
              condition.destinationHubId(),
              condition.address(),
              condition.recipientName(),
              actor.userId());
    }
    Page<Delivery> deliveries = deliveryRepository.search(condition, pageable);
    Map<UUID, List<DeliveryRoute>> routesByDeliveryId = routesOf(deliveries.getContent());
    return deliveries.map(
        delivery ->
            DeliveryResult.of(
                delivery, routesByDeliveryId.getOrDefault(delivery.getId(), List.of())));
  }

  /** [외부 API] GET /api/deliveries/{delivery_id}. 배송 1건을 경로와 함께 읽는다. 없으면 DELIVERY_NOT_FOUND다. */
  public DeliveryResult searchDetail(UUID deliveryId, DeliveryActor actor) {
    Delivery delivery = readableDelivery(deliveryId, actor);
    return DeliveryResult.of(delivery, deliveryRepository.findRoutesByDeliveryId(deliveryId));
  }

  /**
   * [외부 API] GET /api/deliveries/{id}/routes. 배송의 경로를 sequence 순서로 읽는다. 없거나 삭제된 배송이면
   * DELIVERY_NOT_FOUND다.
   */
  public List<DeliveryRouteResult> searchRoutes(UUID id, DeliveryActor actor) {
    readableDelivery(id, actor);
    return deliveryRepository.findRoutesByDeliveryId(id).stream()
        .map(DeliveryRouteResult::from)
        .toList();
  }

  // ---------------------------------------------------------------------------------------------
  // 내부 API (Orders → Delivery). InternalDeliveryController에서 호출한다.
  // ---------------------------------------------------------------------------------------------

  /**
   * [내부 API] GET /internal/deliveries/by-order/{order_id}. 주문의 배송을 읽는다. 아직 생성되지 않았거나 삭제된 배송이면
   * DELIVERY_NOT_FOUND다.
   */
  public DeliveryResult searchByOrderId(UUID orderId) {
    Delivery delivery =
        deliveryRepository
            .findByOrderId(orderId)
            .orElseThrow(() -> new DeliveryBusinessException(DeliveryErrorCode.DELIVERY_NOT_FOUND));
    return DeliveryResult.of(delivery, deliveryRepository.findRoutesByDeliveryId(delivery.getId()));
  }

  // ---------------------------------------------------------------------------------------------
  // 내부 헬퍼
  // ---------------------------------------------------------------------------------------------

  private Delivery readableDelivery(UUID id, DeliveryActor actor) {
    Delivery delivery =
        deliveryRepository
            .findById(id)
            .orElseThrow(() -> new DeliveryBusinessException(DeliveryErrorCode.DELIVERY_NOT_FOUND));
    if (actor.assignedDeliveriesOnly() && !delivery.isAssignedTo(actor.userId())) {
      throw new DeliveryBusinessException(DeliveryErrorCode.DELIVERY_READ_FORBIDDEN);
    }
    return delivery;
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
