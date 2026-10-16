package com.notfound.logistics.users.presentation.dto;

import com.notfound.logistics.users.domain.model.UserRoleEnum;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** MASTER의 사용자 생성 요청입니다. USER는 업체·허브 중 정확히 하나, MASTER는 무소속도 허용합니다. */
public record UserCreateRequestDto(
    @NotBlank @Pattern(regexp = "^[a-z0-9]{4,10}$") String username,
    @NotBlank @Size(max = 100) String name,
    @NotBlank
        @Size(min = 8, max = 15)
        @Pattern(
            regexp = UserUpdateRequestDto.PASSWORD_PATTERN,
            message = UserUpdateRequestDto.PASSWORD_MESSAGE)
        String password,
    @NotNull UserRoleEnum role,
    @NotBlank @Size(max = 100) String slackId,
    UUID companyId,
    UUID hubId,
    Boolean isDeliveryStaff) {

  @AssertTrue(message = "업체 ID와 허브 ID는 함께 입력할 수 없습니다.")
  public boolean isAtMostOneAffiliationProvided() {
    return companyId == null || hubId == null;
  }
}
