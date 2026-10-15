package com.notfound.logistics.company.infrastructure.persistence;

import com.notfound.logistics.company.domain.model.Company;
import com.notfound.logistics.company.domain.model.Product;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CompanyJpaRepository
    extends JpaRepository<Company, UUID>, JpaSpecificationExecutor<Company> {

  // 상품은 Company Aggregate 내부 엔티티라 별도 저장소를 두지 않고 읽기 전용 쿼리만 둔다.
  @Query("SELECT p FROM Product p WHERE p.company.id = :companyId")
  Page<Product> findProducts(@Param("companyId") UUID companyId, Pageable pageable);

  @Query(
      "SELECT p FROM Product p WHERE p.company.id = :companyId"
          + " AND LOWER(p.name) LIKE :pattern ESCAPE '\\'")
  Page<Product> findProductsByName(
      @Param("companyId") UUID companyId, @Param("pattern") String pattern, Pageable pageable);

  @Query("SELECT COUNT(p) FROM Product p WHERE p.company.id = :companyId")
  long countProducts(@Param("companyId") UUID companyId);

  @Query(
      "SELECT COUNT(p) FROM Product p WHERE p.company.id = :companyId"
          + " AND LOWER(p.name) LIKE :pattern ESCAPE '\\'")
  long countProductsByName(@Param("companyId") UUID companyId, @Param("pattern") String pattern);
}
