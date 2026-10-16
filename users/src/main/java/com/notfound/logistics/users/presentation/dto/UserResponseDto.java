package com.notfound.logistics.users.presentation.dto;

import com.notfound.logistics.users.domain.model.User;
import com.notfound.logistics.users.domain.model.UserRoleEnum;
import java.time.Instant;
import java.util.UUID;

public record UserResponseDto(
    UUID id,
    String username,
    String name,
    UserRoleEnum role,
    String slackId,
    UUID companyId,
    UUID hubId,
    boolean isDeliveryStaff,
    Instant createdAt,
    Instant updatedAt) {

  public static UserResponseDto from(User user) {
    return new UserResponseDto(
        user.getId(),
        user.getUsername(),
        user.getName(),
        user.getRole(),
        user.getSlackId(),
        user.getCompanyId(),
        user.getHubId(),
        user.isDeliveryStaff(),
        user.getCreatedAt(),
        user.getUpdatedAt());
  }
}
