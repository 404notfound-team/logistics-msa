package com.notfound.logistics.company.presentation.dto;

import com.notfound.logistics.company.domain.model.Company;
import java.time.Instant;
import java.util.UUID;

public record CompanyResponse(
    UUID companyId,
    String name,
    UUID hubId,
    String address,
    UUID managerUserId,
    Instant createdAt,
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
