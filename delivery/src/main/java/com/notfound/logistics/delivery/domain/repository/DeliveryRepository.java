package com.notfound.logistics.delivery.domain.repository;

import com.notfound.logistics.delivery.domain.model.Delivery;
import com.notfound.logistics.delivery.domain.model.DeliveryRoute;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** 배송 애그리거트 저장소. 구현은 infra 계층이 담당한다. */
public interface DeliveryRepository {
  Page<Delivery> search(DeliverySearchCondition condition, Pageable pageable);

  Optional<Delivery> findById(UUID id);

  /** 배송의 이동 구간을 sequence 순서로 읽는다. */
  List<DeliveryRoute> findRoutesByDeliveryId(UUID deliveryId);
}
