package com.notfound.logistics.company.application;

import com.notfound.logistics.company.domain.Company;
import com.notfound.logistics.company.domain.CompanyRepository;
import com.notfound.logistics.company.presentation.dto.CompanyCreateRequest;
import com.notfound.logistics.company.presentation.dto.CompanyResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CompanyService {
  private final CompanyRepository companyRepository;

  public CompanyService(CompanyRepository companyRepository) {
    this.companyRepository = companyRepository;
  }

  @Transactional
  public CompanyResponse createCompany(CompanyCreateRequest companyCreateRequest) {
    Company company =
        Company.of(
            companyCreateRequest.name(),
            companyCreateRequest.hubId(),
            companyCreateRequest.address(),
            companyCreateRequest.managerUserId());

    companyRepository.save(company);
    return CompanyResponse.from(company);
  }
}
