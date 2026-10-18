package com.notfound.logistics.users.presentation.dto;

import com.notfound.logistics.users.domain.model.UserRoleEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * 보낸 필드만 변경합니다. 생략하거나 null인 필드는 기존 값을 유지합니다. company_id 또는 hub_id 중 하나를 보내면 소속 전체를 그 값으로 바꾸고 다른 쪽은
 * 비웁니다.
 */
@Schema(description = "사용자 수정 요청. 보낸 필드만 변경하고, 생략하거나 null인 필드는 기존 값을 유지합니다.")
public record UserUpdateRequestDto(
    @Schema(description = "이름 (최대 100자)", example = "홍길동")
        @Size(max = 100)
        @Pattern(regexp = NOT_BLANK_PATTERN, message = "공백일 수 없습니다.")
        String name,
    @Schema(description = "비밀번호 (8~15자, 영문 대·소문자·숫자·특수문자 각각 포함)", example = "Passw0rd!")
        @Size(min = 8, max = 15)
        @Pattern(regexp = PASSWORD_PATTERN, message = PASSWORD_MESSAGE)
        String password,
    @Schema(description = "권한", example = "USER") UserRoleEnum role,
    @Schema(description = "Slack ID (최대 100자)", example = "U01ABCDEF")
        @Size(max = 100)
        @Pattern(regexp = NOT_BLANK_PATTERN, message = "공백일 수 없습니다.")
        String slackId,
    @Schema(description = "배송 담당자 여부", example = "true") Boolean isDeliveryStaff,
    @Schema(
            description = "소속 업체 ID. 보내면 허브 소속은 비워집니다.",
            example = "7d752e7e-788b-485b-808e-fd438dfe8bae")
        UUID companyId,
    @Schema(
            description = "소속 허브 ID. 보내면 업체 소속은 비워집니다.",
            example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
        UUID hubId) {

  static final String NOT_BLANK_PATTERN = "(?s).*\\S.*";
  static final String PASSWORD_PATTERN =
      "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9\\s])\\S+$";
  static final String PASSWORD_MESSAGE = "비밀번호는 영문 대문자, 소문자, 숫자, 특수문자를 각각 포함해야 합니다.";

  @Schema(hidden = true)
  @AssertTrue(message = "업체 ID와 허브 ID는 함께 입력할 수 없습니다.")
  public boolean isAtMostOneAffiliationProvided() {
    return companyId == null || hubId == null;
  }

  public boolean changesAffiliation() {
    return companyId != null || hubId != null;
  }
}
