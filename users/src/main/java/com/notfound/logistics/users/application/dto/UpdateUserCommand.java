package com.notfound.logistics.users.application.dto;

import com.notfound.logistics.users.domain.model.UserRoleEnum;
import java.util.UUID;

/** 사용자 수정 입력값. null인 필드는 기존 값을 유지한다. */
public record UpdateUserCommand(
    String name,
    String password,
    UserRoleEnum role,
    String slackId,
    Boolean isDeliveryStaff,
    UUID companyId,
    UUID hubId) {

  /** company_id·hub_id 중 하나라도 보내면 소속 전체를 그 값으로 바꾼다. */
  public boolean changesAffiliation() {
    return companyId != null || hubId != null;
  }
}
