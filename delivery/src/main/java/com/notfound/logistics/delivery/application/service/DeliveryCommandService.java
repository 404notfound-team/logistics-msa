package com.notfound.logistics.delivery.application.service;

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

@Service
@RequiredArgsConstructor
@Transactional
public class DeliveryCommandService {
  private final DeliveryRepository deliveryRepository;

  /**
   * 배송 상태를 바꾸거나 담당자를 수동 배정한다. null인 값은 바꾸지 않는다. 없거나 삭제된 배송이면 DELIVERY_NOT_FOUND다. 변경은 트랜잭션 종료 시
   * dirty checking으로 반영된다.
   *
   * <p>동시 수정은 낙관적 락(Delivery.version)으로 막는다. 같은 배송을 읽은 두 요청 중 먼저 커밋한 쪽만 반영되고, 늦은 쪽은 커밋 시
   * OptimisticLockingFailureException으로 실패해 CONCURRENT_MODIFICATION(409)으로 응답한다. 서버에서 자동 재시도하지 않는다.
   */
  public DeliveryResult modify(UUID deliveryId, DeliveryStatus status, UUID assigneeId) {
    Delivery delivery =
        deliveryRepository
            .findById(deliveryId)
            .orElseThrow(() -> new DeliveryBusinessException(DeliveryErrorCode.DELIVERY_NOT_FOUND));
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
}
