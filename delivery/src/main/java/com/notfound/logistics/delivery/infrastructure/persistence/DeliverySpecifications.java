package com.notfound.logistics.delivery.infrastructure.persistence;

import com.notfound.logistics.delivery.domain.model.Delivery;
import com.notfound.logistics.delivery.domain.repository.DeliverySearchCondition;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

final class DeliverySpecifications {
  private static final char LIKE_ESCAPE = '\\';

  private DeliverySpecifications() {}

  /** 목록과 전체 개수 쿼리가 같은 조건을 쓰도록 하나의 Specification으로 만든다. */
  static Specification<Delivery> matching(DeliverySearchCondition condition) {
    return (root, query, cb) -> {
      List<Predicate> predicates = new ArrayList<>();
      if (condition.orderId() != null) {
        predicates.add(cb.equal(root.get("orderId"), condition.orderId()));
      }
      if (condition.status() != null) {
        predicates.add(cb.equal(root.get("status"), condition.status()));
      }
      if (condition.departureHubId() != null) {
        predicates.add(cb.equal(root.get("departureHubId"), condition.departureHubId()));
      }
      if (condition.destinationHubId() != null) {
        predicates.add(cb.equal(root.get("destinationHubId"), condition.destinationHubId()));
      }
      if (condition.address() != null) {
        predicates.add(
            cb.like(
                cb.lower(root.get("address")), containsPattern(condition.address()), LIKE_ESCAPE));
      }
      if (condition.recipientName() != null) {
        predicates.add(
            cb.like(
                cb.lower(root.get("recipientName")),
                containsPattern(condition.recipientName()),
                LIKE_ESCAPE));
      }
      if (condition.assigneeId() != null) {
        predicates.add(cb.equal(root.get("assigneeId"), condition.assigneeId()));
      }
      return cb.and(predicates.toArray(Predicate[]::new));
    };
  }

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
