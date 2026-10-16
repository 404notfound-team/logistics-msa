package com.notfound.logistics.company.domain.repository;

import com.notfound.logistics.company.domain.model.Company;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** 업체 애그리거트 저장소. 구현은 infra 계층이 담당한다. */
public interface CompanyRepository {
  Company save(Company company);

  Optional<Company> findById(UUID id);

  Page<Company> search(CompanySearchCondition condition, Pageable pageable);
}
