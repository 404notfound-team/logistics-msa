package com.notfound.logistics.company.infrastructure.persistence;

import com.notfound.logistics.company.domain.model.Company;
import com.notfound.logistics.company.domain.model.Product;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CompanyJpaRepository
    extends JpaRepository<Company, UUID>, JpaSpecificationExecutor<Company> {

  List<Company> findAllByName(String name);

  // 다른 트랜잭션이 같은 업체를 잡고 있으면 끝날 때까지 기다린 뒤 최신 행을 읽는다. 그사이 삭제됐다면 조회되지 않는다.
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT c FROM Company c WHERE c.id = :id")
  Optional<Company> findByIdForUpdate(@Param("id") UUID id);

  @Query("SELECT c.managerUserId FROM Company c WHERE c.id = :id")
  Optional<UUID> findManagerUserId(@Param("id") UUID id);

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

  @Query("SELECT p FROM Product p WHERE p.id = :productId")
  Optional<Product> findProductById(@Param("productId") UUID productId);
}
