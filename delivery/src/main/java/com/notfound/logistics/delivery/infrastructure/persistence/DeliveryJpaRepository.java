package com.notfound.logistics.delivery.infrastructure.persistence;

import com.notfound.logistics.delivery.domain.model.Delivery;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface DeliveryJpaRepository
    extends JpaRepository<Delivery, UUID>, JpaSpecificationExecutor<Delivery> {
  // @SQLRestriction으로 삭제된 배송은 제외된다.
  Optional<Delivery> findByOrderId(UUID orderId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select d from Delivery d where d.id = :id")
  Optional<Delivery> findByIdForUpdate(@Param("id") UUID id);

  // 네이티브 쿼리에는 @SQLRestriction이 적용되지 않아 삭제된 배송도 읽힌다.
  @Query(
      value = "SELECT * FROM p_deliveries WHERE delivery_id = :id FOR UPDATE",
      nativeQuery = true)
  Optional<Delivery> findIncludingDeletedForUpdate(@Param("id") UUID id);
}
