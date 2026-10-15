package com.notfound.logistics.users.presentation.dto;

public record UserSignInResponseDto(String accessToken, String tokenType, long expiresIn) {}
