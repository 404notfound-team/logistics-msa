package com.notfound.logistics.users.domain.repository;

import com.notfound.logistics.users.domain.model.User;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

public final class UserSpecifications {
  private static final char LIKE_ESCAPE = '\\';

  private UserSpecifications() {}

  public static Specification<User> matching(UserSearchCondition condition) {
    return (root, query, cb) -> {
      List<Predicate> predicates = new ArrayList<>();
      if (hasText(condition.username())) {
        predicates.add(
            cb.like(root.get("username"), containing(condition.username()), LIKE_ESCAPE));
      }
      if (hasText(condition.name())) {
        predicates.add(cb.like(root.get("name"), containing(condition.name()), LIKE_ESCAPE));
      }
      if (condition.role() != null) {
        predicates.add(cb.equal(root.get("role"), condition.role()));
      }
      if (condition.companyId() != null) {
        predicates.add(cb.equal(root.get("companyId"), condition.companyId()));
      }
      if (condition.hubId() != null) {
        predicates.add(cb.equal(root.get("hubId"), condition.hubId()));
      }
      return cb.and(predicates.toArray(Predicate[]::new));
    };
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private static String containing(String value) {
    String escaped = value.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    return "%" + escaped + "%";
  }
}
