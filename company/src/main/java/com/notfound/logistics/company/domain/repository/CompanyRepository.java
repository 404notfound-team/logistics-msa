package com.notfound.logistics.company.domain.repository;

import com.notfound.logistics.company.domain.model.Company;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** 업체 저장소. 구현은 Spring Data JPA가 만든다. */
public interface CompanyRepository
    extends JpaRepository<Company, UUID>, JpaSpecificationExecutor<Company> {

  /** 업체명(부분 일치, 대소문자 무시)과 허브 ID로 검색한다. null인 조건은 적용하지 않는다. */
  default Page<Company> search(String name, UUID hubId, Pageable pageable) {
    Specification<Company> spec = searchCondition(name, hubId);
    // JPA는 offset(page × size)을 int로 넘겨 범위를 넘으면 예외가 난다.
    // 그런 페이지는 마지막 페이지를 넘긴 요청이므로 목록 없이 같은 조건의 전체 개수만 돌려준다.
    if (pageable.getOffset() > Integer.MAX_VALUE) {
      return new PageImpl<>(List.of(), pageable, count(spec));
    }
    return findAll(spec, pageable);
  }

  // 값이 있는 조건만 붙인다. JPQL의 ":param IS NULL OR ..." 방식은 PostgreSQL에서 null 파라미터 타입을 판단하지 못할 수 있다.
  private static Specification<Company> searchCondition(String name, UUID hubId) {
    List<Specification<Company>> conditions = new ArrayList<>();
    if (name != null && !name.isBlank()) {
      String pattern = "%" + escapeLike(name.strip().toLowerCase(Locale.ROOT)) + "%";
      conditions.add((root, query, cb) -> cb.like(cb.lower(root.get("name")), pattern, '\\'));
    }
    if (hubId != null) {
      conditions.add((root, query, cb) -> cb.equal(root.get("hubId"), hubId));
    }
    return Specification.allOf(conditions);
  }

  // 사용자가 입력한 %, _ 를 와일드카드가 아닌 일반 문자로 검색되게 한다. 이스케이프 문자는 \ 이다.
  private static String escapeLike(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }
}
