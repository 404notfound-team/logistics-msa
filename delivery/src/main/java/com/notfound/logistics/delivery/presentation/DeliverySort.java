package com.notfound.logistics.delivery.presentation;

import java.util.Locale;
import java.util.Map;
import org.springframework.data.domain.Sort;

/** 외부 정렬 키를 허용 목록으로만 엔티티 속성에 변환하고, PK 정렬을 덧붙여 순서를 고정한다. */
final class DeliverySort {
  static final String DEFAULT = "created_at,desc";

  private static final Map<String, String> PROPERTIES =
      Map.of("created_at", "createdAt", "updated_at", "updatedAt");
  private static final String MESSAGE = "정렬은 created_at 또는 updated_at과 asc 또는 desc만 사용할 수 있습니다.";

  private DeliverySort() {}

  static Sort parse(String sort) {
    String[] parts = (sort == null || sort.isBlank() ? DEFAULT : sort).split(",", -1);
    if (parts.length > 2) {
      throw new InvalidRequestException("sort", MESSAGE);
    }
    String property = PROPERTIES.get(parts[0].strip());
    if (property == null) {
      throw new InvalidRequestException("sort", MESSAGE);
    }
    String direction = parts.length == 1 ? "asc" : parts[1].strip().toLowerCase(Locale.ROOT);
    Sort.Order order =
        switch (direction) {
          case "asc" -> Sort.Order.asc(property);
          case "desc" -> Sort.Order.desc(property);
          default -> throw new InvalidRequestException("sort", MESSAGE);
        };
    return Sort.by(order, Sort.Order.asc("id"));
  }
}
