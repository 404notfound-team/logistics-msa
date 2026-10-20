package com.notfound.logistics.users.presentation.dto;

import com.notfound.logistics.users.application.dto.RegisterUserCommand;
import com.notfound.logistics.users.domain.model.UserRoleEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "회원가입 요청. 업체명과 허브명 중 하나만 입력합니다.")
public record UserRegisterRequestDto(
    @Schema(description = "아이디 (영문 소문자·숫자 4~10자)", example = "user01")
        @NotBlank
        @Pattern(regexp = "^[a-z0-9]{4,10}$")
        String username,
    @Schema(description = "이름 (최대 100자)", example = "홍길동") @NotBlank @Size(max = 100) String name,
    @Schema(description = "비밀번호 (8~15자, 영문 대·소문자·숫자·특수문자 각각 포함)", example = "Passw0rd!")
        @NotBlank
        @Size(min = 8, max = 15)
        @Pattern(
            regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9\\s])\\S+$",
            message = "비밀번호는 영문 대문자, 소문자, 숫자, 특수문자를 각각 포함해야 합니다.")
        String password,
    @Schema(description = "권한", example = "USER") @NotNull UserRoleEnum role,
    @Schema(description = "Slack ID (최대 100자)", example = "U01ABCDEF") @NotBlank @Size(max = 100)
        String slackId,
    @Schema(description = "소속 업체명 (최대 100자)", example = "테스트 물류업체") @Size(max = 100)
        String companyName,
    @Schema(description = "소속 허브명 (최대 100자)", example = "서울특별시 센터") @Size(max = 100)
        String hubName) {

  @Schema(hidden = true)
  @AssertTrue(message = "업체명 또는 허브명 중 하나만 입력해야 합니다.")
  public boolean isExactlyOneAffiliationProvided() {
    boolean hasCompany = companyName != null && !companyName.isBlank();
    boolean hasHub = hubName != null && !hubName.isBlank();
    return hasCompany ^ hasHub;
  }

  public RegisterUserCommand toCommand() {
    return new RegisterUserCommand(username, name, password, role, slackId, companyName, hubName);
  }
}
