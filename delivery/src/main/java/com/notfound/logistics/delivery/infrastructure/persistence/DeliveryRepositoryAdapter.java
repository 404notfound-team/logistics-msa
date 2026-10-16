package com.notfound.logistics.delivery.infrastructure.persistence;

import com.notfound.logistics.delivery.domain.model.Delivery;
import com.notfound.logistics.delivery.domain.repository.DeliveryRepository;
import com.notfound.logistics.delivery.domain.repository.DeliverySearchCondition;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class DeliveryRepositoryAdapter implements DeliveryRepository {
  private final DeliveryJpaRepository jpaRepository;

  @Override
  public Page<Delivery> search(DeliverySearchCondition condition, Pageable pageable) {
    Specification<Delivery> specification = DeliverySpecifications.matching(condition);
    // JPA는 int offset만 받는다. 그보다 뒤의 페이지는 항상 마지막 페이지를 넘으므로 개수만 조회한다.
    if (pageable.getOffset() > Integer.MAX_VALUE) {
      return new PageImpl<>(List.of(), pageable, jpaRepository.count(specification));
    }
    return jpaRepository.findAll(specification, pageable);
  }

  @Override
  public Optional<Delivery> findById(UUID id) {
    return jpaRepository.findById(id);
  }
}
