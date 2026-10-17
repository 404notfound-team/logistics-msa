package com.notfound.logistics.company.presentation.controller;

import com.notfound.logistics.common.api.ApiResponse;
import com.notfound.logistics.company.application.service.CompanyService;
import com.notfound.logistics.company.presentation.dto.InternalCompanyResponse;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 서비스 간 호출 전용. Gateway는 /api/** 만 라우팅하고 Swagger도 공개 경로만 문서화하므로 외부에 노출되지 않는다.
// CompanySecurityConfiguration의 서비스 인증(X-Service-Token)으로 호출한다. 단건 조회는 orders, 이름 조회는 users만 허용한다.
@RestController
@RequestMapping("/internal/companies")
@RequiredArgsConstructor
public class InternalCompanyController {
  private final CompanyService companyService;

  /**
   * users: 회원가입 시 소속 업체명을 업체 ID로 바꾼다. 이름이 정확히 같은 업체를 모두 돌려주며, 없으면 빈 목록이다. 0개·여러 개의 판단은 users가 한다. 빈
   * 값·공백만 있는 이름은 조회하지 않고 400으로 응답한다.
   */
  @GetMapping
  public ApiResponse<List<InternalCompanyResponse>> getCompaniesByName(
      @RequestParam("name") @NotBlank String name) {
    return new ApiResponse<>(
        companyService.findCompaniesByName(name).stream()
            .map(InternalCompanyResponse::from)
            .toList());
  }

  /** orders: 주문 생성 시 업체 정보·관리 허브·관리자를 조회한다. 없거나 삭제된 업체는 404 COMPANY_NOT_FOUND. */
  @GetMapping("/{company_id}")
  public ApiResponse<InternalCompanyResponse> getCompany(
      @PathVariable("company_id") UUID companyId) {
    return new ApiResponse<>(InternalCompanyResponse.from(companyService.findCompany(companyId)));
  }
}
