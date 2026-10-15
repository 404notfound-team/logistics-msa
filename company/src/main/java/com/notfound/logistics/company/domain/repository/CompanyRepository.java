package com.notfound.logistics.company.domain.repository;

import com.notfound.logistics.company.domain.model.Company;
import java.util.Optional;
import java.util.UUID;

/** 업체 저장소. 구현은 infrastructure.persistence에 둔다. */
public interface CompanyRepository {
  Company save(Company company);

  Optional<Company> findById(UUID id);
}
