package com.notfound.logistics.users.presentation.controller;

import com.notfound.logistics.common.api.ApiResponse;
import com.notfound.logistics.common.api.PageResponse;
import com.notfound.logistics.users.application.service.UserService;
import com.notfound.logistics.users.domain.exception.UserException;
import com.notfound.logistics.users.domain.model.UserRoleEnum;
import com.notfound.logistics.users.domain.repository.UserSearchCondition;
import com.notfound.logistics.users.presentation.dto.UserCreateRequestDto;
import com.notfound.logistics.users.presentation.dto.UserRegisterRequestDto;
import com.notfound.logistics.users.presentation.dto.UserRegisterResponseDto;
import com.notfound.logistics.users.presentation.dto.UserResponseDto;
import com.notfound.logistics.users.presentation.dto.UserSignInRequestDto;
import com.notfound.logistics.users.presentation.dto.UserSignInResponseDto;
import com.notfound.logistics.users.presentation.dto.UserUpdateRequestDto;
import com.notfound.logistics.users.presentation.exception.UserErrorCode;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/users")
public class UserController {
  /** 외부 정렬 필드(snake_case)와 엔티티 속성의 허용 목록입니다. */
  private static final Map<String, String> SORT_PROPERTIES =
      Map.of(
          "created_at", "createdAt",
          "updated_at", "updatedAt",
          "username", "username",
          "name", "name");

  /**
   * UUID 문자만 받도록 제한해 /signin·/signup·/me 같은 고정 경로가 {id}로 매칭되지 않게 합니다. 이 제한이 없으면 GET /signin이 405 대신
   * UUID 변환 오류가 됩니다.
   */
  private static final String ID_PATH = "/{id:[0-9a-fA-F-]+}";

  private final UserService userService;

  @SecurityRequirements // 토큰 없이 호출하는 공개 API입니다.
  @PostMapping("/signup")
  public ResponseEntity<ApiResponse<UserRegisterResponseDto>> signup(
      @Valid @RequestBody UserRegisterRequestDto request) {
    UserRegisterResponseDto response = userService.register(request);
    return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse<>(response));
  }

  @SecurityRequirements
  @PostMapping("/signin")
  public ApiResponse<UserSignInResponseDto> signin(
      @Valid @RequestBody UserSignInRequestDto request) {
    return new ApiResponse<>(userService.signIn(request));
  }

  /** MASTER 전용입니다. sort는 `필드,방향` 형식이며 기본값은 created_at,desc입니다. */
  @GetMapping
  public ApiResponse<PageResponse<UserResponseDto>> getUsers(
      @RequestParam(name = "username", required = false) String username,
      @RequestParam(name = "name", required = false) String name,
      @RequestParam(name = "role", required = false) UserRoleEnum role,
      @RequestParam(name = "company_id", required = false) UUID companyId,
      @RequestParam(name = "hub_id", required = false) UUID hubId,
      @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
      @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size,
      @RequestParam(name = "sort", defaultValue = "created_at,desc") String sort) {
    var condition = new UserSearchCondition(username, name, role, companyId, hubId);
    return new ApiResponse<>(
        userService.getUsers(condition, PageRequest.of(page, size, toSort(sort))));
  }

  @GetMapping("/me")
  public ApiResponse<UserResponseDto> getMe(@AuthenticationPrincipal Jwt jwt) {
    return new ApiResponse<>(userService.getUser(userId(jwt)));
  }

  @DeleteMapping("/me")
  public ResponseEntity<Void> withdraw(@AuthenticationPrincipal Jwt jwt) {
    userService.withdraw(userId(jwt), jwt.getSubject());
    return ResponseEntity.noContent().build();
  }

  @PostMapping
  public ResponseEntity<ApiResponse<UserResponseDto>> createUser(
      @Valid @RequestBody UserCreateRequestDto request) {
    UserResponseDto response = userService.createUser(request);
    return ResponseEntity.created(URI.create("/api/users/" + response.id()))
        .body(new ApiResponse<>(response));
  }

  @GetMapping(ID_PATH)
  public ApiResponse<UserResponseDto> getUser(@PathVariable("id") UUID id) {
    return new ApiResponse<>(userService.getUser(id));
  }

  @PatchMapping(ID_PATH)
  public ApiResponse<UserResponseDto> updateUser(
      @PathVariable("id") UUID id, @Valid @RequestBody UserUpdateRequestDto request) {
    return new ApiResponse<>(userService.updateUser(id, request));
  }

  @DeleteMapping(ID_PATH)
  public ResponseEntity<Void> deleteUser(
      @PathVariable("id") UUID id, @AuthenticationPrincipal Jwt jwt) {
    userService.deleteUser(id, jwt.getSubject());
    return ResponseEntity.noContent().build();
  }

  private UUID userId(Jwt jwt) {
    return UUID.fromString(jwt.getClaimAsString("user_id"));
  }

  /** 허용한 필드만 정렬에 쓰고, 같은 값이면 PK로 순서를 고정합니다. */
  private Sort toSort(String sort) {
    String[] parts = sort.split(",", -1);
    if (parts.length > 2) {
      throw new UserException(UserErrorCode.INVALID_SORT);
    }
    String property = SORT_PROPERTIES.get(parts[0].trim());
    if (property == null) {
      throw new UserException(UserErrorCode.INVALID_SORT);
    }
    Sort.Direction direction = Sort.Direction.ASC;
    if (parts.length == 2) {
      direction =
          Sort.Direction.fromOptionalString(parts[1].trim())
              .orElseThrow(() -> new UserException(UserErrorCode.INVALID_SORT));
    }
    return Sort.by(direction, property).and(Sort.by(Sort.Direction.ASC, "id"));
  }
}
