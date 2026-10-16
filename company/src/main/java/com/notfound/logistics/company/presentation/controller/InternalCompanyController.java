package com.notfound.logistics.company.presentation.controller;

import com.notfound.logistics.common.api.ApiResponse;
import com.notfound.logistics.company.application.service.CompanyService;
import com.notfound.logistics.company.presentation.dto.InternalCompanyResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 서비스 간 호출 전용. Gateway는 /api/** 만 라우팅하고 Swagger도 공개 경로만 문서화하므로 외부에 노출되지 않는다.
// TODO(auth, #46): hub와 같은 서비스 인증을 적용한다.
//  - X-Service-Token으로 호출 서비스를 인증해 orders만 허용한다.
//  - X-Actor-Username은 서비스 인증 후에만 감사 주체로 쓴다.
//  - 참고: hub의 HubSecurityConfiguration, HubServiceAuthenticationFilter
@RestController
@RequestMapping("/internal/companies")
@RequiredArgsConstructor
public class InternalCompanyController {
  private final CompanyService companyService;

  /** orders: 주문 생성 시 업체 정보·관리 허브·관리자를 조회한다. 없거나 삭제된 업체는 404 COMPANY_NOT_FOUND. */
  @GetMapping("/{company_id}")
  public ApiResponse<InternalCompanyResponse> getCompany(
      @PathVariable("company_id") UUID companyId) {
    return new ApiResponse<>(InternalCompanyResponse.from(companyService.findCompany(companyId)));
  }
}
