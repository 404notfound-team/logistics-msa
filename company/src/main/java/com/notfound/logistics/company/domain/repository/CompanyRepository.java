package com.notfound.logistics.company.domain.repository;

import com.notfound.logistics.company.domain.model.Company;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** 업체 저장소. 구현은 infrastructure.persistence에 둔다. */
public interface CompanyRepository {
  Company save(Company company);

  Optional<Company> findById(UUID id);

  /** 업체명(부분 일치)과 허브 ID로 검색한다. null인 조건은 적용하지 않는다. */
  Page<Company> search(String name, UUID hubId, Pageable pageable);
}
