package com.notfound.logistics.company.infrastructure.persistence;

import com.notfound.logistics.company.domain.model.Company;
import com.notfound.logistics.company.domain.repository.CompanyRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** 도메인 저장소 인터페이스를 Spring Data JPA로 구현한다. */
@Repository
@RequiredArgsConstructor
class CompanyRepositoryAdapter implements CompanyRepository {
  private final CompanyJpaRepository jpaRepository;

  @Override
  public Company save(Company company) {
    return jpaRepository.save(company);
  }

  @Override
  public Optional<Company> findById(UUID id) {
    return jpaRepository.findById(id);
  }
}
