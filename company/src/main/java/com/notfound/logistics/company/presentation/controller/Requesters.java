package com.notfound.logistics.company.presentation.controller;

import com.notfound.logistics.company.application.service.Requester;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/** 인증된 JWT를 서비스의 요청자로 바꾼다. user_id·role 형식은 CompanySecurityConfiguration이 이미 검증했다. */
final class Requesters {
  private Requesters() {}

  static Requester from(Jwt principal) {
    return new Requester(
        UUID.fromString(principal.getClaimAsString("user_id")),
        "MASTER".equals(principal.getClaimAsString("role")));
  }
}
