package com.notfound.logistics.users.application.dto;

import java.time.Duration;

/** 로그인·갱신으로 함께 발급한 access token과 refresh token입니다. */
public record IssuedTokens(
    String accessToken, Duration accessTokenTtl, String refreshToken, Duration refreshTokenTtl) {}
