package com.notfound.logistics.users.presentation.controller;

import com.notfound.logistics.common.api.ApiResponse;
import com.notfound.logistics.users.application.service.UserService;
import com.notfound.logistics.users.presentation.dto.UserRegisterRequestDto;
import com.notfound.logistics.users.presentation.dto.UserRegisterResponseDto;
import com.notfound.logistics.users.presentation.dto.UserSignInRequestDto;
import com.notfound.logistics.users.presentation.dto.UserSignInResponseDto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/users")
public class UserController {

  private final UserService userService;

  @PostMapping("/signup")
  public ResponseEntity<ApiResponse<UserRegisterResponseDto>> signup(
      @Valid @RequestBody UserRegisterRequestDto request) {
    UserRegisterResponseDto response = userService.register(request);
    return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse<>(response));
  }

  @PostMapping("/signin")
  public ApiResponse<UserSignInResponseDto> signin(
      @Valid @RequestBody UserSignInRequestDto request) {
    return new ApiResponse<>(userService.signIn(request));
  }
}
