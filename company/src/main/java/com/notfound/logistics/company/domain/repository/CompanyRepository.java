package com.notfound.logistics.company.domain.repository;

import com.notfound.logistics.company.domain.model.Company;
import com.notfound.logistics.company.domain.model.Product;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** 업체 애그리거트 저장소. 구현은 infra 계층이 담당한다. */
public interface CompanyRepository {
  Company save(Company company);

  Optional<Company> findById(UUID id);

  /**
   * 변경할 업체를 행 잠금(SELECT ... FOR UPDATE)으로 조회한다. 업체와 소속 상품의 변경이 업체 단위로 직렬화되어, 동시 수정의 유실과 삭제된 업체·상품의
   * 복원을 막는다. 잠금은 트랜잭션이 끝날 때 풀린다.
   */
  Optional<Company> findByIdForUpdate(UUID id);

  Page<Company> search(CompanySearchCondition condition, Pageable pageable);

  /** 이름이 정확히 같은 업체를 모두 찾는다. 삭제된 업체는 제외한다. */
  List<Company> findAllByName(String name);

  /**
   * 업체의 상품을 페이지로 조회한다. 상품은 Company Aggregate의 내부 엔티티이므로 변경은 Company를 통해서 하고, 페이지 조회만 이 읽기 전용 조회로
   * 한다. 업체 존재 여부는 호출하는 쪽에서 먼저 확인한다.
   */
  Page<Product> searchProducts(ProductSearchCondition condition, Pageable pageable);

  /** 업체 ID 없이 상품 ID만으로 읽기 전용 조회한다. 다른 서비스의 내부 조회용이며, 상품 변경은 Company를 통해서 한다. */
  Optional<Product> findProductById(UUID productId);
}
