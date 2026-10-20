package com.notfound.logistics.users.presentation.dto;

import com.notfound.logistics.users.domain.model.User;
import com.notfound.logistics.users.domain.model.UserRoleEnum;
import java.util.UUID;

public record UserAuthorizationResponseDto(
    UUID userId,
    String username,
    boolean active,
    UserRoleEnum role,
    boolean isDeliveryStaff,
    long tokenVersion) {

  public static UserAuthorizationResponseDto from(User user) {
    return new UserAuthorizationResponseDto(
        user.getId(),
        user.getUsername(),
        true,
        user.getRole(),
        user.isDeliveryStaff(),
        user.getTokenVersion());
  }

  /** 탈퇴·삭제했거나 없는 사용자입니다. */
  public static UserAuthorizationResponseDto inactive(UUID id) {
    return new UserAuthorizationResponseDto(id, null, false, null, false, 0);
  }
}
