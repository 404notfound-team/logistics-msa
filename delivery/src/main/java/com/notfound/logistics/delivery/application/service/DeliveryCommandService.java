package com.notfound.logistics.delivery.application.service;

import com.notfound.logistics.delivery.application.dto.DeliveryActor;
import com.notfound.logistics.delivery.application.dto.DeliveryResult;
import com.notfound.logistics.delivery.domain.exception.DeliveryBusinessException;
import com.notfound.logistics.delivery.domain.exception.DeliveryErrorCode;
import com.notfound.logistics.delivery.domain.model.Delivery;
import com.notfound.logistics.delivery.domain.model.DeliveryStatus;
import com.notfound.logistics.delivery.domain.repository.DeliveryRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.notfound.logistics.delivery.domain.model.DeliveryRoute;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.AuditorAware;

@Service
@RequiredArgsConstructor
@Transactional
public class DeliveryCommandService {
  private final DeliveryRepository deliveryRepository;
  private final AuditorAware<String> auditorAware;

  /**
   * 배송 상태를 바꾸거나 담당자를 수동 배정한다. null인 값은 바꾸지 않는다. 없거나 삭제된 배송이면 DELIVERY_NOT_FOUND다. 변경은 트랜잭션 종료 시
   * dirty checking으로 반영된다.
   *
   * <p>동시 수정은 낙관적 락(Delivery.version)으로 막는다. 같은 배송을 읽은 두 요청 중 먼저 커밋한 쪽만 반영되고, 늦은 쪽은 커밋 시
   * OptimisticLockingFailureException으로 실패해 CONCURRENT_MODIFICATION(409)으로 응답한다. 서버에서 자동 재시도하지 않는다.
   *
   * <p>담당자 배정은 MASTER만(ASSIGNMENT_FORBIDDEN), 상태 변경은 MASTER 또는 요청 시점의 배송 담당자만
   * (STATUS_CHANGE_FORBIDDEN) 할 수 있다. 하나라도 권한이 없으면 아무것도 바꾸지 않는다.
   */
  public DeliveryResult modify(
      UUID deliveryId, DeliveryStatus status, UUID assigneeId, DeliveryActor actor) {
    // 배정 권한은 역할만으로 정해지므로 배송을 읽기 전에 거른다.
    if (assigneeId != null && !actor.master()) {
      throw new DeliveryBusinessException(DeliveryErrorCode.ASSIGNMENT_FORBIDDEN);
    }
    Delivery delivery =
        deliveryRepository
            .findById(deliveryId)
            .orElseThrow(() -> new DeliveryBusinessException(DeliveryErrorCode.DELIVERY_NOT_FOUND));
    if (status != null && !actor.master() && !delivery.isAssignedTo(actor.userId())) {
      throw new DeliveryBusinessException(DeliveryErrorCode.STATUS_CHANGE_FORBIDDEN);
    }
    // 배정 가능 여부는 요청 전의 현재 상태로 판단해야 하므로 상태보다 먼저 배정한다.
    // (예: HUB_WAITING인 배송에 담당자 배정과 HUB_MOVING 변경을 함께 요청하면 허용한다)
    if (assigneeId != null) {
      delivery.assign(assigneeId);
    }
    if (status != null) {
      delivery.changeStatus(status);
    }
    return DeliveryResult.of(delivery, deliveryRepository.findRoutesByDeliveryId(deliveryId));
  }
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
