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
   */
  public DeliveryResult modify(UUID deliveryId, DeliveryStatus status, UUID assigneeId) {
    Delivery delivery =
        deliveryRepository
            .findById(deliveryId)
            .orElseThrow(() -> new DeliveryBusinessException(DeliveryErrorCode.DELIVERY_NOT_FOUND));
    if (status != null) {
      delivery.changeStatus(status);
    }
    if (assigneeId != null) {
      delivery.assign(assigneeId);
    }
    return DeliveryResult.of(delivery, deliveryRepository.findRoutesByDeliveryId(deliveryId));
  }
}
