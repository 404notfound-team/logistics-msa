package com.notfound.logistics.users.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record UserSignInRequestDto(
    @NotBlank @Pattern(regexp = "^[a-z0-9]{4,10}$") String username,
    @NotBlank String password) {}
