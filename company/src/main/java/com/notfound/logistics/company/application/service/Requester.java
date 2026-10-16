package com.notfound.logistics.company.application.service;

import java.util.Objects;
import java.util.UUID;

/**
 * 공개 API 요청자. 컨트롤러가 인증된 JWT의 user_id·role에서 만들어 넘긴다. 별도의 업체 관리자 역할은 없고, 업체의 manager_user_id가 요청자
 * ID와 같으면 그 업체의 관리자로 본다.
 */
public record Requester(UUID userId, boolean master) {
  public Requester {
    Objects.requireNonNull(userId, "userId");
  }

  boolean manages(UUID managerUserId) {
    return userId.equals(managerUserId);
  }
}
