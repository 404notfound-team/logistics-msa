package com.notfound.logistics.delivery.domain.repository;

import com.notfound.logistics.delivery.domain.model.Delivery;
import com.notfound.logistics.delivery.domain.model.DeliveryRoute;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** 배송 애그리거트 저장소. 구현은 infra 계층이 담당한다. */
public interface DeliveryRepository {
  Page<Delivery> search(DeliverySearchCondition condition, Pageable pageable);

  Optional<Delivery> findById(UUID id);

  /** 주문의 배송(삭제되지 않은 것)을 찾는다. 주문 1건에는 배송이 최대 1건이다. */
  Optional<Delivery> findByOrderId(UUID orderId);

  /** 배송을 저장하고 저장된(ID가 생성된) 배송을 반환한다. */
  Delivery save(Delivery delivery);

  /** 배송의 이동 구간들을 저장한다. */
  List<DeliveryRoute> saveRoutes(List<DeliveryRoute> routes);

  /** 배송의 이동 구간을 sequence 순서로 읽는다. */
  List<DeliveryRoute> findRoutesByDeliveryId(UUID deliveryId);

  /** 여러 배송의 이동 구간을 한 번에 sequence 순서로 읽는다. 목록 조회의 N+1을 피한다. */
  List<DeliveryRoute> findRoutesByDeliveryIds(Collection<UUID> deliveryIds);
}
