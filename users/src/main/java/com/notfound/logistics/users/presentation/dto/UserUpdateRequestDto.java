package com.notfound.logistics.users.presentation.dto;

import com.notfound.logistics.users.domain.model.UserRoleEnum;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * 보낸 필드만 변경합니다. 생략하거나 null인 필드는 기존 값을 유지합니다. company_id 또는 hub_id 중 하나를 보내면 소속 전체를 그 값으로 바꾸고 다른 쪽은
 * 비웁니다.
 */
public record UserUpdateRequestDto(
    @Size(max = 100) @Pattern(regexp = NOT_BLANK_PATTERN, message = "공백일 수 없습니다.") String name,
    @Size(min = 8, max = 15) @Pattern(regexp = PASSWORD_PATTERN, message = PASSWORD_MESSAGE)
        String password,
    UserRoleEnum role,
    @Size(max = 100) @Pattern(regexp = NOT_BLANK_PATTERN, message = "공백일 수 없습니다.") String slackId,
    Boolean isDeliveryStaff,
    UUID companyId,
    UUID hubId) {

  static final String NOT_BLANK_PATTERN = "(?s).*\\S.*";
  static final String PASSWORD_PATTERN =
      "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9\\s])\\S+$";
  static final String PASSWORD_MESSAGE = "비밀번호는 영문 대문자, 소문자, 숫자, 특수문자를 각각 포함해야 합니다.";

  @AssertTrue(message = "업체 ID와 허브 ID는 함께 입력할 수 없습니다.")
  public boolean isAtMostOneAffiliationProvided() {
    return companyId == null || hubId == null;
  }

  public boolean changesAffiliation() {
    return companyId != null || hubId != null;
  }
}
