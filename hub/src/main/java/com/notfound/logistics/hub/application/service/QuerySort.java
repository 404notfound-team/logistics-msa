package com.notfound.logistics.hub.application.service;

import java.util.Locale;
import org.springframework.data.domain.Sort;

/** 외부 정렬 이름을 명시적으로 허용한 엔티티 속성으로만 변환한다. */
public final class QuerySort {
  private QuerySort() {}

  public static Sort resolve(String sort, String defaultDirection) {
    String[] parts = (sort == null ? "created_at," + defaultDirection : sort).split(",", -1);
    if (parts.length != 2) throw new IllegalArgumentException("Invalid sort");
    String property =
        switch (parts[0].toLowerCase(Locale.ROOT)) {
          case "created_at" -> "createdAt";
          case "id" -> "id";
          default -> throw new IllegalArgumentException("Unsupported sort property");
        };
    Sort result = Sort.by(Sort.Direction.fromString(parts[1]), property);
    return "id".equals(property) ? result : result.and(Sort.by(Sort.Direction.DESC, "id"));
  }
}
