package com.notfound.logistics.company.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** JSON 필드는 전역 snake_case 규칙을 따른다: name, hub_id, address, manager_user_id. */
public record CompanyCreateRequest(
    @NotBlank @Size(max = 100) String name,
    @NotNull UUID hubId,
    @NotBlank @Size(max = 255) String address,
    @NotNull UUID managerUserId) {}
