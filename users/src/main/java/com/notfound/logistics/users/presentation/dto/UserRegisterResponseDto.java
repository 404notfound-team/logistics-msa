package com.notfound.logistics.users.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "회원가입 결과")
public record UserRegisterResponseDto(
    @Schema(description = "가입한 사용자 ID", example = "22222222-2222-2222-2222-222222222222")
        UUID id) {}
