package com.notfound.logistics.delivery.application.service;

import com.notfound.logistics.delivery.application.dto.DeliveryResult;
import com.notfound.logistics.delivery.domain.exception.DeliveryBusinessException;
import com.notfound.logistics.delivery.domain.exception.DeliveryErrorCode;
import com.notfound.logistics.delivery.domain.repository.DeliveryRepository;
import com.notfound.logistics.delivery.domain.repository.DeliverySearchCondition;
import java.util.UUID;
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
    return deliveryRepository.search(condition, pageable).map(DeliveryResult::from);
  }

  public DeliveryResult searchDetail(UUID deliveryId) {
    return deliveryRepository
        .findById(deliveryId)
        .map(DeliveryResult::from)
        .orElseThrow(() -> new DeliveryBusinessException(DeliveryErrorCode.DELIVERY_NOT_FOUND));
  }
}
