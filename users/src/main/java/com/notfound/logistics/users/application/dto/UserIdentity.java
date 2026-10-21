package com.notfound.logistics.users.application.dto;

import com.notfound.logistics.users.domain.model.User;
import java.util.UUID;

/** 알림의 표시 이름을 위한 최소 사용자 정보입니다. */
public record UserIdentity(UUID id, String username, String name) {
  public static UserIdentity from(User user) {
    return new UserIdentity(user.getId(), user.getUsername(), user.getName());
  }
}
