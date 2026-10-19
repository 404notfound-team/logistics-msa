package com.notfound.logistics.hub.presentation.controller.hub;

import com.notfound.logistics.common.api.ApiErrorResponse;
import com.notfound.logistics.common.api.ApiResponse;
import com.notfound.logistics.common.api.PageResponse;
import com.notfound.logistics.hub.application.dto.hub.HubResult;
import com.notfound.logistics.hub.application.service.hub.HubQueryService;
import com.notfound.logistics.hub.presentation.dto.hub.HubResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@ApiResponses({
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "400",
      description = "요청 형식 또는 값 검증 실패",
      content =
          @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = ApiErrorResponse.class),
              examples =
                  @ExampleObject(
                      name = "INVALID_REQUEST",
                      value =
                          "{\"code\": \"INVALID_REQUEST\", \"message\": \"요청 값이 올바르지 않습니다.\", \"trace_id\": null, \"field_errors\": []}"))),
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
                          "{\"code\": \"INTERNAL_ERROR\", \"message\": \"요청 처리 중 오류가 발생했습니다.\", \"trace_id\": null, \"field_errors\": []}"))),
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "401",
      description = "토큰 누락·만료·위조 또는 현재 사용자 인증 실패",
      content =
          @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = ApiErrorResponse.class),
              examples =
                  @ExampleObject(
                      name = "UNAUTHORIZED",
                      value =
                          "{\"code\": \"UNAUTHORIZED\", \"message\": \"인증이 필요합니다.\", \"trace_id\": null, \"field_errors\": []}"))),
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
@Tag(name = "Hub", description = "허브 조회 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/hubs")
public class HubController {

  private final HubQueryService hubQueryService;

  @Operation(
      summary = "허브 목록·검색",
      description =
          "허브명 부분 일치(대소문자 무시)로 검색합니다. sort는 created_at 또는 id와 asc/desc를 지정합니다. 생략하면 direction을 적용합니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "조회 성공",
        useReturnTypeSchema = true)
  })
  @GetMapping
  public ApiResponse<PageResponse<HubResponse>> getHubs(
      @Parameter(description = "페이지 번호 (0부터 시작)") @RequestParam(defaultValue = "0") @Min(0)
          int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
      @RequestParam(defaultValue = "desc") @Pattern(regexp = "(?i)asc|desc") String direction,
      @RequestParam(required = false) @Size(max = 100) String name,
      @RequestParam(required = false) @Pattern(regexp = "(?i)(created_at|id),(asc|desc)")
          String sort) {

    Page<HubResult> result = hubQueryService.getHubs(page, size, direction, name, sort);

    return new ApiResponse<>(
        new PageResponse<>(
            result.getContent().stream().map(HubResponse::from).toList(),
            result.getNumber(),
            result.getSize(),
            result.getTotalElements()));
  }

  @Operation(summary = "허브 상세 조회", description = "허브 ID로 허브 정보를 조회합니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "조회 성공",
        useReturnTypeSchema = true),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "허브 없음",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = {
                  @ExampleObject(
                      name = "HUB_NOT_FOUND",
                      value =
                          "{\"code\": \"HUB_NOT_FOUND\", \"message\": \"허브를 찾을 수 없습니다.\", \"trace_id\": null, \"field_errors\": []}")
                }))
  })
  @GetMapping("/{id}")
  public ApiResponse<HubResponse> getHub(@PathVariable UUID id) {

    HubResponse response = HubResponse.from(hubQueryService.getHub(id));

    return new ApiResponse<>(response);
  }
}
