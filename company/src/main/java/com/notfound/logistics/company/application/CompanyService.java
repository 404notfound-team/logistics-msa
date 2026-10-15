package com.notfound.logistics.company.application;

import com.notfound.logistics.company.domain.Company;
import com.notfound.logistics.company.domain.CompanyRepository;
import com.notfound.logistics.company.presentation.dto.CompanyCreateRequest;
import com.notfound.logistics.company.presentation.dto.CompanyResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CompanyService {
  private final CompanyRepository companyRepository;

  @Transactional
  public CompanyResponse createCompany(CompanyCreateRequest companyCreateRequest) {
    // TODO(auth): Gateway 인증 구현 후 요청자 정보(사용자 ID·역할)를 받아 권한을 검사한다.
    //  - MASTER: 제한 없음
    //  - 업체 관리자: manager_user_id가 본인 ID일 때만 생성 가능
    //  - 그 외 역할: 403
    // TODO(hub): hub 서비스 내부 API(Feign)로 hub_id가 실제 존재하는 허브인지 확인한다.
    //  - 경로·응답 형식은 hub 담당자와 합의 (예: GET /internal/hubs/{hubId})
    //  - 없는 허브면 CompanyErrorCode의 허브 없음 오류, hub 서비스 장애면 503
    // TODO(users): manager_user_id가 업체 관리자 역할의 실제 사용자인지 users 서비스로 확인할지 팀과 결정한다.
    //  - 확인한다면 users 내부 API(Feign) 호출, 사용자 ID 타입(Long 가정)도 함께 확정

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
