package com.notfound.logistics.company.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** JSON 필드는 전역 snake_case 규칙을 따른다: name, hub_id, address, manager_user_id. */
@Schema(description = "업체 생성 요청")
public record CompanyCreateRequest(
    @Schema(description = "업체명 (최대 100자)", example = "테스트 물류업체") @NotBlank @Size(max = 100)
        String name,
    @Schema(description = "관리 허브 ID", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6") @NotNull
        UUID hubId,
    @Schema(description = "업체 주소 (최대 255자)", example = "서울특별시 강남구 테헤란로 123")
        @NotBlank
        @Size(max = 255)
        String address,
    @Schema(description = "업체 관리자 사용자 ID", example = "22222222-2222-2222-2222-222222222222")
        @NotNull
        UUID managerUserId) {}
