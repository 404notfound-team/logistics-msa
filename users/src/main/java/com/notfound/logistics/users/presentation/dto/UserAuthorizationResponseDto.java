package com.notfound.logistics.users.presentation.dto;

import com.notfound.logistics.users.domain.model.UserRoleEnum;
import java.util.UUID;

public record UserAuthorizationResponseDto(
    UUID userId, String username, boolean active, UserRoleEnum role, boolean isDeliveryStaff) {}
