package com.notfound.logistics.company.infrastructure.persistence;

import com.notfound.logistics.company.domain.model.Company;
import com.notfound.logistics.company.domain.repository.CompanySearchCondition;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

final class CompanySpecifications {
  private static final char LIKE_ESCAPE = '\\';

  private CompanySpecifications() {}

  /** 목록과 전체 개수 쿼리가 같은 조건을 쓰도록 하나의 Specification으로 만든다. */
  static Specification<Company> matching(CompanySearchCondition condition) {
    return (root, query, cb) -> {
      // 값이 있는 조건만 붙인다. JPQL의 ":param IS NULL OR ..." 방식은 PostgreSQL에서 null 파라미터 타입을 판단하지 못할 수 있다.
      List<Predicate> predicates = new ArrayList<>();
      if (condition.name() != null) {
        predicates.add(
            cb.like(cb.lower(root.get("name")), containsPattern(condition.name()), LIKE_ESCAPE));
      }
      if (condition.hubId() != null) {
        predicates.add(cb.equal(root.get("hubId"), condition.hubId()));
      }
      return cb.and(predicates.toArray(Predicate[]::new));
    };
  }

  // 사용자가 입력한 %, _ 를 와일드카드가 아닌 일반 문자로 검색되게 한다.
  private static String containsPattern(String keyword) {
    String escaped =
        keyword
            .toLowerCase(Locale.ROOT)
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_");
    return "%" + escaped + "%";
  }
}
