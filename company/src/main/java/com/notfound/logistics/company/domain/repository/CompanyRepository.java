package com.notfound.logistics.company.domain.repository;

import com.notfound.logistics.company.domain.model.Company;
import com.notfound.logistics.company.domain.model.Product;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** 업체 애그리거트 저장소. 구현은 infra 계층이 담당한다. */
public interface CompanyRepository {
  Company save(Company company);

  Optional<Company> findById(UUID id);

  Page<Company> search(CompanySearchCondition condition, Pageable pageable);

  /**
   * 업체의 상품을 페이지로 조회한다. 상품은 Company Aggregate의 내부 엔티티이므로 변경은 Company를 통해서 하고, 페이지 조회만 이 읽기 전용 조회로
   * 한다. 업체 존재 여부는 호출하는 쪽에서 먼저 확인한다.
   */
  Page<Product> searchProducts(ProductSearchCondition condition, Pageable pageable);
}
