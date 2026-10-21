package com.notfound.logistics.company.presentation.dto;

import com.notfound.logistics.company.domain.model.Company;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "업체 정보")
public record CompanyResponse(
    @Schema(description = "업체 ID", example = "7d752e7e-788b-485b-808e-fd438dfe8bae") UUID id,
    @Schema(description = "업체명", example = "테스트 물류업체") String name,
    @Schema(description = "관리 허브 ID", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6") UUID hubId,
    @Schema(description = "업체 주소", example = "서울특별시 강남구 테헤란로 123") String address,
    @Schema(description = "업체 관리자 사용자 ID", example = "22222222-2222-2222-2222-222222222222")
        UUID managerUserId,
    @Schema(description = "생성 시각 (UTC)", example = "2026-10-15T06:31:26.904Z") Instant createdAt,
    @Schema(description = "마지막 수정 시각 (UTC)", example = "2026-10-15T06:31:26.904Z")
        Instant updatedAt) {

  public static CompanyResponse from(Company company) {
    return new CompanyResponse(
        company.getId(),
        company.getName(),
        company.getHubId(),
        company.getAddress(),
        company.getManagerUserId(),
        company.getCreatedAt(),
        company.getUpdatedAt());
  }
}
