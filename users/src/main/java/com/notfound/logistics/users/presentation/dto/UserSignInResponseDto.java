package com.notfound.logistics.users.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "로그인 결과")
public record UserSignInResponseDto(
    @Schema(description = "JWT 액세스 토큰", example = "eyJhbGciOiJSUzI1NiJ9...") String accessToken,
    @Schema(description = "토큰 타입", example = "Bearer") String tokenType,
    @Schema(description = "토큰 만료까지 남은 시간 (초)", example = "3600") long expiresIn) {}
