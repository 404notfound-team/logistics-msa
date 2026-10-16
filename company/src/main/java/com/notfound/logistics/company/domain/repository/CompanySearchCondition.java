package com.notfound.logistics.company.domain.repository;

import java.util.UUID;

/** null인 조건은 적용하지 않는다. 업체명은 대소문자 구분 없는 부분 일치로 검색한다. */
public record CompanySearchCondition(String name, UUID hubId) {
  public CompanySearchCondition {
    name = name == null || name.isBlank() ? null : name.strip();
  }
}
