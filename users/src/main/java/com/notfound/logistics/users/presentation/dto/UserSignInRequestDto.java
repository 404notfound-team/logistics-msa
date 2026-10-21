package com.notfound.logistics.users.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@Schema(description = "로그인 요청")
public record UserSignInRequestDto(
    @Schema(description = "아이디", example = "user01") @NotBlank @Pattern(regexp = "^[a-z0-9]{4,10}$")
        String username,
    @Schema(description = "비밀번호", example = "Passw0rd!") @NotBlank String password) {}
