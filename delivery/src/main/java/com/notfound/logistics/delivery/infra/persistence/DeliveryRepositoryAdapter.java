package com.notfound.logistics.delivery.infra.persistence;

import com.notfound.logistics.delivery.domain.model.Delivery;
import com.notfound.logistics.delivery.domain.repository.DeliveryRepository;
import com.notfound.logistics.delivery.domain.repository.DeliverySearchCondition;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class DeliveryRepositoryAdapter implements DeliveryRepository {
  private final DeliveryJpaRepository jpaRepository;

  @Override
  public Page<Delivery> search(DeliverySearchCondition condition, Pageable pageable) {
    return jpaRepository.findAll(DeliverySpecifications.matching(condition), pageable);
  }
}
