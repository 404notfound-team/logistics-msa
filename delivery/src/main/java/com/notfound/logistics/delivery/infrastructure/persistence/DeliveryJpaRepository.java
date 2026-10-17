package com.notfound.logistics.delivery.infrastructure.persistence;

import com.notfound.logistics.delivery.domain.model.Delivery;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

interface DeliveryJpaRepository
    extends JpaRepository<Delivery, UUID>, JpaSpecificationExecutor<Delivery> {
  // @SQLRestriction으로 삭제된 배송은 제외된다.
  Optional<Delivery> findByOrderId(UUID orderId);
}
