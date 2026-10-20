package com.notfound.logistics.users.presentation.controller;

import com.notfound.logistics.common.api.ApiErrorResponse;
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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
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

// Swagger의 ApiResponse는 공통 응답 타입 ApiResponse와 이름이 같아 전체 패키지 이름으로 쓴다.
// 오류 응답에는 ApiErrorResponse 형식을 지정한다. 지정하지 않으면 성공 응답 타입이 모든 상태 코드에 표시된다.

@ApiResponses({
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "500",
      description = "예상 밖 서버 오류",
      content =
          @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = ApiErrorResponse.class),
              examples =
                  @ExampleObject(
                      name = "INTERNAL_ERROR",
                      value =
                          "{\"code\": \"INTERNAL_ERROR\", \"message\": \"요청 처리 중 오류가 발생했습니다.\", \"trace_id\": null, \"field_errors\": []}")))
})
@Tag(name = "User", description = "회원가입·로그인·사용자 조회·관리 API")
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

  @Operation(
      summary = "회원가입",
      description = "새 사용자를 가입시킵니다. 소속은 업체명 또는 허브명 중 하나만 입력하며, 이름으로 업체·허브를 찾아 연결합니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "201",
        description = "가입 성공"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "요청 값 검증 실패 (아이디·비밀번호 형식 오류, 소속 누락·중복 입력)",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = @ExampleObject(value = ErrorExamples.SIGNUP_INVALID_EXAMPLE))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "입력한 업체 또는 허브를 찾을 수 없음 (ENTITY_NOT_FOUND)",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = @ExampleObject(value = ErrorExamples.AFFILIATION_NOT_FOUND_EXAMPLE))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "409",
        description =
            "이미 사용 중인 아이디 (USERNAME_ALREADY_EXISTS), 같은 이름의 업체·허브가 여러 개 (AFFILIATION_AMBIGUOUS)",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = {
                  @ExampleObject(
                      name = "USERNAME_ALREADY_EXISTS",
                      value = ErrorExamples.USERNAME_ALREADY_EXISTS_EXAMPLE),
                  @ExampleObject(
                      name = "AFFILIATION_AMBIGUOUS",
                      value = ErrorExamples.AFFILIATION_AMBIGUOUS_EXAMPLE)
                })),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "503",
        description = "업체 또는 허브 정보를 확인할 수 없음 (AFFILIATION_LOOKUP_UNAVAILABLE)",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples =
                    @ExampleObject(value = ErrorExamples.AFFILIATION_LOOKUP_UNAVAILABLE_EXAMPLE))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "415",
        description = "지원하지 않는 요청 본문 형식",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples =
                    @ExampleObject(
                        name = "UNSUPPORTED_MEDIA_TYPE",
                        value =
                            "{\"code\": \"UNSUPPORTED_MEDIA_TYPE\", \"message\": \"지원하지 않는 요청 형식입니다.\", \"trace_id\": null, \"field_errors\": []}")))
  })
  @SecurityRequirements // 토큰 없이 호출하는 공개 API입니다.
  @PostMapping("/signup")
  public ResponseEntity<ApiResponse<UserRegisterResponseDto>> signup(
      @Valid @RequestBody UserRegisterRequestDto request) {
    UserRegisterResponseDto response = userService.register(request);
    return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse<>(response));
  }

  @Operation(summary = "로그인", description = "아이디와 비밀번호로 로그인하고 JWT 액세스 토큰을 발급합니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "로그인 성공"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "요청 값 검증 실패 (아이디 형식 오류, 비밀번호 누락)",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = @ExampleObject(value = ErrorExamples.SIGNIN_INVALID_EXAMPLE))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "401",
        description = "아이디 또는 비밀번호 불일치 (INVALID_CREDENTIALS)",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = @ExampleObject(value = ErrorExamples.INVALID_CREDENTIALS_EXAMPLE))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "415",
        description = "지원하지 않는 요청 본문 형식",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples =
                    @ExampleObject(
                        name = "UNSUPPORTED_MEDIA_TYPE",
                        value =
                            "{\"code\": \"UNSUPPORTED_MEDIA_TYPE\", \"message\": \"지원하지 않는 요청 형식입니다.\", \"trace_id\": null, \"field_errors\": []}")))
  })
  @SecurityRequirements
  @PostMapping("/signin")
  public ApiResponse<UserSignInResponseDto> signin(
      @Valid @RequestBody UserSignInRequestDto request) {
    return new ApiResponse<>(userService.signIn(request));
  }

  /** MASTER 전용입니다. sort는 `필드,방향` 형식이며 기본값은 created_at,desc입니다. */
  @Operation(
      summary = "사용자 목록·검색 (MASTER)",
      description = "아이디·이름·권한·소속으로 사용자를 검색합니다. 탈퇴한 사용자는 조회되지 않습니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "조회 성공"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "검색 조건 오류 (page·size 범위 초과, 지원하지 않는 정렬 조건 INVALID_SORT)",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = @ExampleObject(value = ErrorExamples.LIST_INVALID_EXAMPLE))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "401",
        description = "인증 필요 (UNAUTHORIZED)",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = @ExampleObject(value = ErrorExamples.UNAUTHORIZED_EXAMPLE))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "403",
        description = "MASTER가 아님 (FORBIDDEN)",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = @ExampleObject(value = ErrorExamples.FORBIDDEN_EXAMPLE))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "503",
        description = "Gateway가 현재 사용자 정보를 확인할 수 없음",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples =
                    @ExampleObject(
                        name = "SERVICE_UNAVAILABLE",
                        value =
                            "{\"code\": \"SERVICE_UNAVAILABLE\", \"message\": \"서비스를 일시적으로 사용할 수 없습니다.\", \"trace_id\": null, \"field_errors\": []}")))
  })
  @GetMapping
  public ApiResponse<PageResponse<UserResponseDto>> getUsers(
      @Parameter(description = "아이디 (부분 일치)", example = "master01")
          @RequestParam(name = "username", required = false)
          String username,
      @Parameter(description = "이름 (부분 일치)", example = "홍길동")
          @RequestParam(name = "name", required = false)
          String name,
      @Parameter(description = "권한") @RequestParam(name = "role", required = false)
          UserRoleEnum role,
      @Parameter(description = "소속 업체 ID") @RequestParam(name = "company_id", required = false)
          UUID companyId,
      @Parameter(description = "소속 허브 ID") @RequestParam(name = "hub_id", required = false)
          UUID hubId,
      @Parameter(description = "페이지 번호 (0부터)")
          @RequestParam(name = "page", defaultValue = "0")
          @Min(0)
          int page,
      @Parameter(description = "페이지 크기 (1~100)")
          @RequestParam(name = "size", defaultValue = "20")
          @Min(1)
          @Max(100)
          int size,
      @Parameter(
              description = "정렬 `필드,방향`. 필드는 created_at, updated_at, username, name",
              example = "created_at,desc")
          @RequestParam(name = "sort", defaultValue = "created_at,desc")
          String sort) {
    var condition = new UserSearchCondition(username, name, role, companyId, hubId);
    return new ApiResponse<>(
        userService.getUsers(condition, PageRequest.of(page, size, toSort(sort))));
  }

  @Operation(summary = "내 정보 조회", description = "로그인한 사용자 본인의 정보를 조회합니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "조회 성공"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "401",
        description = "인증 필요 (UNAUTHORIZED)",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = @ExampleObject(value = ErrorExamples.UNAUTHORIZED_EXAMPLE))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "사용자를 찾을 수 없음 (USER_NOT_FOUND)",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = @ExampleObject(value = ErrorExamples.USER_NOT_FOUND_EXAMPLE))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "503",
        description = "Gateway가 현재 사용자 정보를 확인할 수 없음",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples =
                    @ExampleObject(
                        name = "SERVICE_UNAVAILABLE",
                        value =
                            "{\"code\": \"SERVICE_UNAVAILABLE\", \"message\": \"서비스를 일시적으로 사용할 수 없습니다.\", \"trace_id\": null, \"field_errors\": []}")))
  })
  @GetMapping("/me")
  public ApiResponse<UserResponseDto> getMe(
      @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
    return new ApiResponse<>(userService.getUser(userId(jwt)));
  }

  @Operation(summary = "회원 탈퇴", description = "로그인한 사용자 본인의 계정을 탈퇴 처리합니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "204",
        description = "탈퇴 성공"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "401",
        description = "인증 필요 (UNAUTHORIZED)",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = @ExampleObject(value = ErrorExamples.UNAUTHORIZED_EXAMPLE))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "사용자를 찾을 수 없음 (USER_NOT_FOUND)",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = @ExampleObject(value = ErrorExamples.USER_NOT_FOUND_EXAMPLE))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "503",
        description = "Gateway가 현재 사용자 정보를 확인할 수 없음",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples =
                    @ExampleObject(
                        name = "SERVICE_UNAVAILABLE",
                        value =
                            "{\"code\": \"SERVICE_UNAVAILABLE\", \"message\": \"서비스를 일시적으로 사용할 수 없습니다.\", \"trace_id\": null, \"field_errors\": []}")))
  })
  @DeleteMapping("/me")
  public ResponseEntity<Void> withdraw(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
    userService.withdraw(userId(jwt), jwt.getSubject());
    return ResponseEntity.noContent().build();
  }

  @Operation(
      summary = "로그아웃",
      description = "로그인한 사용자 본인이 발급받은 모든 JWT를 무효화합니다. 다른 기기의 로그인도 함께 종료됩니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "204",
        description = "로그아웃 성공"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "401",
        description = "인증 필요 (UNAUTHORIZED)",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = @ExampleObject(value = ErrorExamples.UNAUTHORIZED_EXAMPLE))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "사용자를 찾을 수 없음 (USER_NOT_FOUND)",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = @ExampleObject(value = ErrorExamples.USER_NOT_FOUND_EXAMPLE))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "503",
        description = "Gateway가 현재 사용자 정보를 확인할 수 없음",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples =
                    @ExampleObject(
                        name = "SERVICE_UNAVAILABLE",
                        value =
                            "{\"code\": \"SERVICE_UNAVAILABLE\", \"message\": \"서비스를 일시적으로 사용할 수 없습니다.\", \"trace_id\": null, \"field_errors\": []}")))
  })
  @PostMapping("/signout")
  public ResponseEntity<Void> signout(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
    userService.signOut(userId(jwt));
    return ResponseEntity.noContent().build();
  }

  @Operation(
      summary = "사용자 생성 (MASTER)",
      description = "MASTER가 사용자를 직접 생성합니다. 성공 시 Location 헤더로 생성된 사용자의 상세 조회 경로를 반환합니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "201",
        description = "생성 성공"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "요청 값 검증 실패, 권한에 맞지 않는 소속 (INVALID_AFFILIATION)",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = {
                  @ExampleObject(
                      name = "INVALID_REQUEST",
                      value = ErrorExamples.CREATE_INVALID_EXAMPLE),
                  @ExampleObject(
                      name = "INVALID_AFFILIATION",
                      value = ErrorExamples.INVALID_AFFILIATION_EXAMPLE)
                })),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "401",
        description = "인증 필요 (UNAUTHORIZED)",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = @ExampleObject(value = ErrorExamples.UNAUTHORIZED_EXAMPLE))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "403",
        description = "MASTER가 아님 (FORBIDDEN)",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = @ExampleObject(value = ErrorExamples.FORBIDDEN_EXAMPLE))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "업체 또는 허브를 찾을 수 없음 (ENTITY_NOT_FOUND)",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = @ExampleObject(value = ErrorExamples.AFFILIATION_NOT_FOUND_EXAMPLE))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "409",
        description = "이미 사용 중인 아이디 (USERNAME_ALREADY_EXISTS)",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = @ExampleObject(value = ErrorExamples.USERNAME_ALREADY_EXISTS_EXAMPLE))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "503",
        description =
            "업체 또는 허브 정보를 확인할 수 없음 (AFFILIATION_LOOKUP_UNAVAILABLE) 또는 Gateway 사용자 확인 실패 (SERVICE_UNAVAILABLE)",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = {
                  @ExampleObject(
                      name = "업무 연동 실패",
                      value = ErrorExamples.AFFILIATION_LOOKUP_UNAVAILABLE_EXAMPLE),
                  @ExampleObject(
                      name = "SERVICE_UNAVAILABLE",
                      value =
                          "{\"code\": \"SERVICE_UNAVAILABLE\", \"message\": \"서비스를 일시적으로 사용할 수 없습니다.\", \"trace_id\": null, \"field_errors\": []}")
                })),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "415",
        description = "지원하지 않는 요청 본문 형식",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples =
                    @ExampleObject(
                        name = "UNSUPPORTED_MEDIA_TYPE",
                        value =
                            "{\"code\": \"UNSUPPORTED_MEDIA_TYPE\", \"message\": \"지원하지 않는 요청 형식입니다.\", \"trace_id\": null, \"field_errors\": []}")))
  })
  @PostMapping
  public ResponseEntity<ApiResponse<UserResponseDto>> createUser(
      @Valid @RequestBody UserCreateRequestDto request) {
    UserResponseDto response = userService.createUser(request);
    return ResponseEntity.created(URI.create("/api/users/" + response.id()))
        .body(new ApiResponse<>(response));
  }

  @Operation(
      summary = "사용자 상세 조회 (MASTER)",
      description = "사용자 ID로 사용자 정보를 조회합니다. 탈퇴한 사용자는 조회되지 않습니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "조회 성공"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "잘못된 사용자 ID 형식",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = @ExampleObject(value = ErrorExamples.INVALID_ID_EXAMPLE))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "401",
        description = "인증 필요 (UNAUTHORIZED)",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = @ExampleObject(value = ErrorExamples.UNAUTHORIZED_EXAMPLE))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "403",
        description = "MASTER가 아님 (FORBIDDEN)",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = @ExampleObject(value = ErrorExamples.FORBIDDEN_EXAMPLE))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "사용자를 찾을 수 없음 (USER_NOT_FOUND)",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = @ExampleObject(value = ErrorExamples.USER_NOT_FOUND_EXAMPLE))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "503",
        description = "Gateway가 현재 사용자 정보를 확인할 수 없음",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples =
                    @ExampleObject(
                        name = "SERVICE_UNAVAILABLE",
                        value =
                            "{\"code\": \"SERVICE_UNAVAILABLE\", \"message\": \"서비스를 일시적으로 사용할 수 없습니다.\", \"trace_id\": null, \"field_errors\": []}")))
  })
  @GetMapping(ID_PATH)
  public ApiResponse<UserResponseDto> getUser(
      @Parameter(description = "사용자 ID", example = "22222222-2222-2222-2222-222222222222")
          @PathVariable("id")
          UUID id) {
    return new ApiResponse<>(userService.getUser(id));
  }

  @Operation(
      summary = "사용자 수정 (MASTER)",
      description = "보낸 필드만 변경합니다. company_id 또는 hub_id 중 하나를 보내면 소속 전체를 그 값으로 바꾸고 다른 쪽은 비웁니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "수정 성공"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "요청 값 검증 실패, 잘못된 사용자 ID 형식, 권한에 맞지 않는 소속 (INVALID_AFFILIATION)",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = {
                  @ExampleObject(
                      name = "INVALID_REQUEST",
                      value = ErrorExamples.UPDATE_INVALID_EXAMPLE),
                  @ExampleObject(
                      name = "INVALID_AFFILIATION",
                      value = ErrorExamples.INVALID_AFFILIATION_EXAMPLE)
                })),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "401",
        description = "인증 필요 (UNAUTHORIZED)",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = @ExampleObject(value = ErrorExamples.UNAUTHORIZED_EXAMPLE))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "403",
        description = "MASTER가 아님 (FORBIDDEN)",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = @ExampleObject(value = ErrorExamples.FORBIDDEN_EXAMPLE))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "사용자를 찾을 수 없음 (USER_NOT_FOUND), 업체 또는 허브를 찾을 수 없음 (ENTITY_NOT_FOUND)",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = {
                  @ExampleObject(
                      name = "USER_NOT_FOUND",
                      value = ErrorExamples.USER_NOT_FOUND_EXAMPLE),
                  @ExampleObject(
                      name = "ENTITY_NOT_FOUND",
                      value = ErrorExamples.AFFILIATION_NOT_FOUND_EXAMPLE)
                })),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "503",
        description =
            "업체 또는 허브 정보를 확인할 수 없음 (AFFILIATION_LOOKUP_UNAVAILABLE) 또는 Gateway 사용자 확인 실패 (SERVICE_UNAVAILABLE)",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = {
                  @ExampleObject(
                      name = "업무 연동 실패",
                      value = ErrorExamples.AFFILIATION_LOOKUP_UNAVAILABLE_EXAMPLE),
                  @ExampleObject(
                      name = "SERVICE_UNAVAILABLE",
                      value =
                          "{\"code\": \"SERVICE_UNAVAILABLE\", \"message\": \"서비스를 일시적으로 사용할 수 없습니다.\", \"trace_id\": null, \"field_errors\": []}")
                })),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "415",
        description = "지원하지 않는 요청 본문 형식",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples =
                    @ExampleObject(
                        name = "UNSUPPORTED_MEDIA_TYPE",
                        value =
                            "{\"code\": \"UNSUPPORTED_MEDIA_TYPE\", \"message\": \"지원하지 않는 요청 형식입니다.\", \"trace_id\": null, \"field_errors\": []}")))
  })
  @PatchMapping(ID_PATH)
  public ApiResponse<UserResponseDto> updateUser(
      @Parameter(description = "사용자 ID", example = "22222222-2222-2222-2222-222222222222")
          @PathVariable("id")
          UUID id,
      @Valid @RequestBody UserUpdateRequestDto request) {
    return new ApiResponse<>(userService.updateUser(id, request));
  }

  @Operation(summary = "사용자 삭제 (MASTER)", description = "사용자를 삭제 처리합니다. 삭제된 사용자는 로그인과 조회가 되지 않습니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "204",
        description = "삭제 성공"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "잘못된 사용자 ID 형식",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = @ExampleObject(value = ErrorExamples.INVALID_ID_EXAMPLE))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "401",
        description = "인증 필요 (UNAUTHORIZED)",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = @ExampleObject(value = ErrorExamples.UNAUTHORIZED_EXAMPLE))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "403",
        description = "MASTER가 아님 (FORBIDDEN)",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = @ExampleObject(value = ErrorExamples.FORBIDDEN_EXAMPLE))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "사용자를 찾을 수 없음 (USER_NOT_FOUND)",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = @ExampleObject(value = ErrorExamples.USER_NOT_FOUND_EXAMPLE))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "503",
        description = "Gateway가 현재 사용자 정보를 확인할 수 없음",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples =
                    @ExampleObject(
                        name = "SERVICE_UNAVAILABLE",
                        value =
                            "{\"code\": \"SERVICE_UNAVAILABLE\", \"message\": \"서비스를 일시적으로 사용할 수 없습니다.\", \"trace_id\": null, \"field_errors\": []}")))
  })
  @DeleteMapping(ID_PATH)
  public ResponseEntity<Void> deleteUser(
      @Parameter(description = "사용자 ID", example = "22222222-2222-2222-2222-222222222222")
          @PathVariable("id")
          UUID id,
      @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
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
