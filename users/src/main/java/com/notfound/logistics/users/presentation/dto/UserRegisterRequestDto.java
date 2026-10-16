package com.notfound.logistics.users.presentation.dto;

import com.notfound.logistics.users.domain.model.UserRoleEnum;
import jakarta.validation.constraints.*;

public record UserRegisterRequestDto(
    @NotBlank @Pattern(regexp = "^[a-z0-9]{4,10}$") String username,
    @NotBlank @Size(max = 100) String name,
    @NotBlank
        @Size(min = 8, max = 15)
        @Pattern(
            regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9\\s])\\S+$",
            message = "비밀번호는 영문 대문자, 소문자, 숫자, 특수문자를 각각 포함해야 합니다.")
        String password,
    @NotNull UserRoleEnum role,
    @NotBlank @Size(max = 100) String slackId,
    @Size(max = 100) String companyName,
    @Size(max = 100) String hubName) {

  @AssertTrue(message = "업체명 또는 허브명 중 하나만 입력해야 합니다.")
  public boolean isExactlyOneAffiliationProvided() {
    boolean hasCompany = companyName != null && !companyName.isBlank();
    boolean hasHub = hubName != null && !hubName.isBlank();
    return hasCompany ^ hasHub;
  }
}
