package com.notfound.logistics.company.presentation.dto;

import com.notfound.logistics.company.domain.model.Company;
import java.util.UUID;

/**
 * 서비스 간 내부 API 응답. 공개 API 응답(CompanyResponse)과 분리해 내부 계약 변경이 외부에 영향을 주지 않게 한다. ID 필드는 hub 내부 API와
 * 같이 id로 내려준다.
 */
public record InternalCompanyResponse(
    UUID id, String name, UUID hubId, String address, UUID managerUserId) {

  public static InternalCompanyResponse from(Company company) {
    return new InternalCompanyResponse(
        company.getId(),
        company.getName(),
        company.getHubId(),
        company.getAddress(),
        company.getManagerUserId());
  }
}
