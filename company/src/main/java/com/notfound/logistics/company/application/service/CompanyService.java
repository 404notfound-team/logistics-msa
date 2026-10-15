package com.notfound.logistics.company.application.service;

import com.notfound.logistics.company.domain.exception.CompanyError;
import com.notfound.logistics.company.domain.exception.CompanyException;
import com.notfound.logistics.company.domain.model.Company;
import com.notfound.logistics.company.domain.repository.CompanyRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CompanyService {
  private final CompanyRepository companyRepository;

  @Transactional
  public Company createCompany(String name, UUID hubId, String address, UUID managerUserId) {
    // TODO(auth): Gateway 인증 구현 후 요청자 정보(사용자 ID·역할)를 받아 권한을 검사한다.
    //  - MASTER: 제한 없음
    //  - 업체 관리자: manager_user_id가 본인 ID일 때만 생성 가능
    //  - 그 외 역할: 403
    // TODO(hub): hub 서비스 내부 API(Feign)로 hub_id가 실제 존재하는 허브인지 확인한다.
    //  - 경로·응답 형식은 hub 담당자와 합의 (예: GET /internal/hubs/{hubId})
    //  - 없는 허브면 CompanyError.HUB_NOT_FOUND, hub 서비스 장애면 503
    // TODO(users): manager_user_id가 업체 관리자 역할의 실제 사용자인지 users 서비스로 확인할지 팀과 결정한다.
    //  - 확인한다면 users 내부 API(Feign) 호출

    Company company = Company.of(name, hubId, address, managerUserId);
    companyRepository.save(company);
    return company;
  }

  @Transactional(readOnly = true)
  public Company findCompany(UUID id) {
    // 삭제된 업체는 엔티티의 @SQLRestriction("deleted_at IS NULL")으로 조회되지 않아 여기서 함께 404가 된다.
    return companyRepository
        .findById(id)
        .orElseThrow(() -> new CompanyException(CompanyError.COMPANY_NOT_FOUND));
  }

  @Transactional(readOnly = true)
  public Page<Company> searchCompanies(String name, UUID hubId, Pageable pageable) {
    // 목록과 전체 개수는 같은 검색·삭제 제외 조건으로 조회된다.
    return companyRepository.search(name, hubId, pageable);
  }
}
