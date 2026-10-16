package com.notfound.logistics.delivery.infrastructure.persistence;

import com.notfound.logistics.delivery.domain.model.DeliveryRoute;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface DeliveryRouteJpaRepository extends JpaRepository<DeliveryRoute, UUID> {
  // Delivery의 @SQLRestriction 때문에 p_deliveries와 join하며, 삭제된 배송의 구간은 나오지 않는다.
  List<DeliveryRoute> findAllByDeliveryIdOrderBySequenceAsc(UUID deliveryId);

  List<DeliveryRoute> findAllByDeliveryIdInOrderBySequenceAsc(Collection<UUID> deliveryIds);
}
