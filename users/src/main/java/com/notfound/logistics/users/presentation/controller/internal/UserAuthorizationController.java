package com.notfound.logistics.users.presentation.controller.internal;

import com.notfound.logistics.common.api.ApiResponse;
import com.notfound.logistics.users.application.service.UserService;
import com.notfound.logistics.users.presentation.dto.UserAuthorizationResponseDto;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/users")
public class UserAuthorizationController {
  private final UserService userService;

  @GetMapping("/{id}/authorization")
  public ApiResponse<UserAuthorizationResponseDto> getAuthorization(@PathVariable UUID id) {
    return new ApiResponse<>(userService.getAuthorization(id));
  }
}
