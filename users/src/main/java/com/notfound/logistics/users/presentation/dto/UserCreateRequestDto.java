package com.notfound.logistics.users.presentation.dto;

import com.notfound.logistics.users.application.dto.CreateUserCommand;
import com.notfound.logistics.users.domain.model.UserRoleEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** MASTER의 사용자 생성 요청입니다. USER는 업체·허브 중 정확히 하나, MASTER는 무소속도 허용합니다. */
@Schema(description = "사용자 생성 요청. USER는 업체·허브 중 정확히 하나, MASTER는 무소속도 허용합니다.")
public record UserCreateRequestDto(
    @Schema(description = "아이디 (영문 소문자·숫자 4~10자)", example = "user01")
        @NotBlank
        @Pattern(regexp = "^[a-z0-9]{4,10}$")
        String username,
    @Schema(description = "이름 (최대 100자)", example = "홍길동") @NotBlank @Size(max = 100) String name,
    @Schema(description = "비밀번호 (8~15자, 영문 대·소문자·숫자·특수문자 각각 포함)", example = "Passw0rd!")
        @NotBlank
        @Size(min = 8, max = 15)
        @Pattern(
            regexp = UserUpdateRequestDto.PASSWORD_PATTERN,
            message = UserUpdateRequestDto.PASSWORD_MESSAGE)
        String password,
    @Schema(description = "권한", example = "USER") @NotNull UserRoleEnum role,
    @Schema(description = "Slack ID (최대 100자)", example = "U01ABCDEF") @NotBlank @Size(max = 100)
        String slackId,
    @Schema(description = "소속 업체 ID", example = "7d752e7e-788b-485b-808e-fd438dfe8bae")
        UUID companyId,
    @Schema(description = "소속 허브 ID", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6") UUID hubId,
    @Schema(description = "배송 담당자 여부 (생략 시 false)", example = "false") Boolean isDeliveryStaff) {

  @Schema(hidden = true)
  @AssertTrue(message = "업체 ID와 허브 ID는 함께 입력할 수 없습니다.")
  public boolean isAtMostOneAffiliationProvided() {
    return companyId == null || hubId == null;
  }

  public CreateUserCommand toCommand() {
    return new CreateUserCommand(
        username, name, password, role, slackId, companyId, hubId, isDeliveryStaff);
  }
}
