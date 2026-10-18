package com.notfound.logistics.users.presentation.dto;

import com.notfound.logistics.users.domain.model.User;
import com.notfound.logistics.users.domain.model.UserRoleEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "사용자 정보")
public record UserResponseDto(
    @Schema(description = "사용자 ID", example = "22222222-2222-2222-2222-222222222222") UUID id,
    @Schema(description = "아이디", example = "user01") String username,
    @Schema(description = "이름", example = "홍길동") String name,
    @Schema(description = "권한", example = "USER") UserRoleEnum role,
    @Schema(description = "Slack ID", example = "U01ABCDEF") String slackId,
    @Schema(description = "소속 업체 ID (없으면 null)", example = "7d752e7e-788b-485b-808e-fd438dfe8bae")
        UUID companyId,
    @Schema(description = "소속 허브 ID (없으면 null)", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
        UUID hubId,
    @Schema(description = "배송 담당자 여부", example = "false") boolean isDeliveryStaff,
    @Schema(description = "생성 시각 (UTC)", example = "2026-10-15T06:31:26.904Z") Instant createdAt,
    @Schema(description = "마지막 수정 시각 (UTC)", example = "2026-10-15T06:31:26.904Z")
        Instant updatedAt) {

  public static UserResponseDto from(User user) {
    return new UserResponseDto(
        user.getId(),
        user.getUsername(),
        user.getName(),
        user.getRole(),
        user.getSlackId(),
        user.getCompanyId(),
        user.getHubId(),
        user.isDeliveryStaff(),
        user.getCreatedAt(),
        user.getUpdatedAt());
  }
}
