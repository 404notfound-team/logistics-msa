package com.notfound.logistics.users.dto;

import java.util.UUID;

public record UserRegisterResponseDto(
  UUID id,
  String username
) {}
