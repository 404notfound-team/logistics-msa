package com.notfound.logistics.users.dto;

public record UserSignInResponseDto(String accessToken, String tokenType, long expiresIn) {}
