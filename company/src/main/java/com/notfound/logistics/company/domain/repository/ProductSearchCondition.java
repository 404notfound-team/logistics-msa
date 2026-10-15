package com.notfound.logistics.company.domain.repository;

import java.util.Objects;
import java.util.UUID;

/** 업체 ID는 필수다. 상품명은 대소문자 구분 없는 부분 일치로 검색하며 null이면 적용하지 않는다. */
public record ProductSearchCondition(UUID companyId, String name) {
  public ProductSearchCondition {
    Objects.requireNonNull(companyId, "companyId");
    name = name == null || name.isBlank() ? null : name.strip();
  }
}
