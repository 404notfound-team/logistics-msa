package com.notfound.logistics.company.infrastructure.persistence;

import com.notfound.logistics.company.domain.model.Company;
import com.notfound.logistics.company.domain.repository.CompanyRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

/** 도메인 저장소 인터페이스를 Spring Data JPA로 구현한다. */
@Repository
@RequiredArgsConstructor
class CompanyRepositoryAdapter implements CompanyRepository {
  private static final char LIKE_ESCAPE = '\\';

  private final CompanyJpaRepository jpaRepository;

  @Override
  public Company save(Company company) {
    return jpaRepository.save(company);
  }

  @Override
  public Optional<Company> findById(UUID id) {
    return jpaRepository.findById(id);
  }

  // 값이 있는 조건만 붙인다. JPQL의 ":param IS NULL OR ..." 방식은 PostgreSQL에서 null 파라미터 타입을 판단하지 못할 수 있다.
  @Override
  public Page<Company> search(String name, UUID hubId, Pageable pageable) {
    List<Specification<Company>> conditions = new ArrayList<>();
    if (name != null && !name.isBlank()) {
      String pattern = "%" + escapeLike(name.strip().toLowerCase(Locale.ROOT)) + "%";
      conditions.add(
          (root, query, cb) -> cb.like(cb.lower(root.get("name")), pattern, LIKE_ESCAPE));
    }
    if (hubId != null) {
      conditions.add((root, query, cb) -> cb.equal(root.get("hubId"), hubId));
    }
    Specification<Company> spec = Specification.allOf(conditions);
    // JPA는 offset(page × size)을 int로 넘겨 범위를 넘으면 예외가 난다.
    // 그런 페이지는 마지막 페이지를 넘긴 요청이므로 목록 없이 같은 조건의 전체 개수만 돌려준다.
    if (pageable.getOffset() > Integer.MAX_VALUE) {
      return new PageImpl<>(List.of(), pageable, jpaRepository.count(spec));
    }
    return jpaRepository.findAll(spec, pageable);
  }

  // 사용자가 입력한 %, _ 를 와일드카드가 아닌 일반 문자로 검색되게 한다.
  private static String escapeLike(String value) {
    return value
        .replace(String.valueOf(LIKE_ESCAPE), "" + LIKE_ESCAPE + LIKE_ESCAPE)
        .replace("%", LIKE_ESCAPE + "%")
        .replace("_", LIKE_ESCAPE + "_");
  }
}
