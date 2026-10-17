package com.notfound.logistics.delivery.application.service;

import com.notfound.logistics.delivery.application.dto.DeliveryResult;
import com.notfound.logistics.delivery.domain.exception.DeliveryBusinessException;
import com.notfound.logistics.delivery.domain.exception.DeliveryErrorCode;
import com.notfound.logistics.delivery.domain.model.Delivery;
import com.notfound.logistics.delivery.domain.model.DeliveryRoute;
import com.notfound.logistics.delivery.domain.repository.DeliveryRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.AuditorAware;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이미 생성된 배송과 배송의 경로를 함께 변경한다. 같은 배송의 동시 변경은 쓰기 잠금으로 순서대로 처리한다.
 *
 * <p>배송 생성은 Hub 호출과 트랜잭션 경계가 달라 DeliveryCreationService가, 조회는 DeliveryQueryService가 맡는다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class DeliveryCommandService {
  private final DeliveryRepository deliveryRepository;
  // 삭제 작업자는 created_by·updated_by와 같은 X-Actor-Username이다.
  private final AuditorAware<String> auditorAware;

  // ---------------------------------------------------------------------------------------------
  // 내부 API (Orders → Delivery). InternalDeliveryController에서 호출한다.
  // ---------------------------------------------------------------------------------------------

  /**
   * [내부 API] POST /internal/deliveries/{id}/cancel. 배송과 배송의 모든 경로를 취소한다. 이미 취소된 배송의 재요청은 현재 배송을 그대로
   * 반환한다.
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

  /**
   * [내부 API] DELETE /internal/deliveries/{id}. 배송과 배송의 모든 경로를 논리 삭제한다. 이미 삭제된 배송의 재요청은 아무것도 바꾸지
   * 않는다.
   *
   * @throws DeliveryBusinessException 배송이 없으면 DELIVERY_NOT_FOUND, 끝나지 않았으면 DELIVERY_IN_PROGRESS
   */
  public void delete(UUID deliveryId) {
    Delivery delivery =
        deliveryRepository
            .findIncludingDeletedForUpdate(deliveryId)
            .orElseThrow(() -> new DeliveryBusinessException(DeliveryErrorCode.DELIVERY_NOT_FOUND));
    if (delivery.getDeletedAt() != null) {
      return;
    }
    // 경로는 배송을 삭제하기 전에 읽는다. 경로 조회는 배송의 @SQLRestriction(deleted_at IS NULL)을 거치므로, 삭제 표시가
    // flush된 뒤에 읽으면 경로가 하나도 조회되지 않는다.
    List<DeliveryRoute> routes = deliveryRepository.findRoutesByDeliveryId(deliveryId);
    String username = auditorAware.getCurrentAuditor().orElseThrow();
    Instant deletedAt = Instant.now();
    delivery.delete(username, deletedAt);
    for (DeliveryRoute route : routes) {
      route.markDeleted(username, deletedAt);
    }
  }
}
