package com.notfound.logistics.company.infrastructure.persistence;

import com.notfound.logistics.company.domain.model.Company;
import com.notfound.logistics.company.domain.model.Product;
import com.notfound.logistics.company.domain.repository.CompanyRepository;
import com.notfound.logistics.company.domain.repository.CompanySearchCondition;
import com.notfound.logistics.company.domain.repository.ProductSearchCondition;
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

  @Override
  public Page<Company> search(CompanySearchCondition condition, Pageable pageable) {
    Specification<Company> specification = CompanySpecifications.matching(condition);
    // JPA는 offset(page × size)을 int로 넘겨 범위를 넘으면 예외가 난다.
    // 그런 페이지는 마지막 페이지를 넘긴 요청이므로 목록 없이 같은 조건의 전체 개수만 돌려준다.
    if (pageable.getOffset() > Integer.MAX_VALUE) {
      return new PageImpl<>(List.of(), pageable, jpaRepository.count(specification));
    }
    return jpaRepository.findAll(specification, pageable);
  }

  @Override
  public Page<Product> searchProducts(ProductSearchCondition condition, Pageable pageable) {
    UUID companyId = condition.companyId();
    String pattern =
        condition.name() == null ? null : CompanySpecifications.containsPattern(condition.name());
    // 업체 검색과 같은 이유로 int 범위를 넘는 offset은 목록 없이 전체 개수만 돌려준다.
    if (pageable.getOffset() > Integer.MAX_VALUE) {
      long total =
          pattern == null
              ? jpaRepository.countProducts(companyId)
              : jpaRepository.countProductsByName(companyId, pattern);
      return new PageImpl<>(List.of(), pageable, total);
    }
    return pattern == null
        ? jpaRepository.findProducts(companyId, pageable)
        : jpaRepository.findProductsByName(companyId, pattern, pageable);
  }

  @Override
  public Optional<Product> findProductById(UUID productId) {
    return jpaRepository.findProductById(productId);
  }
}
