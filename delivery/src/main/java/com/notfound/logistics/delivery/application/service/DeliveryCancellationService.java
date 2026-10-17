package com.notfound.logistics.delivery.application.service;

import com.notfound.logistics.delivery.application.dto.DeliveryResult;
import com.notfound.logistics.delivery.domain.repository.DeliveryRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class DeliveryCancellationService {
  private final DeliveryRepository deliveryRepository;

  /** 배송과 배송의 모든 경로를 취소한다. */
  public DeliveryResult cancel(UUID deliveryId) {
    // TODO: 배송·경로 취소 로직 구현
    throw new UnsupportedOperationException("배송 취소는 아직 구현되지 않았다.");
  }
}
