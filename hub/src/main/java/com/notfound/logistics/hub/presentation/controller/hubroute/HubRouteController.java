package com.notfound.logistics.hub.presentation.controller.hubroute;

import com.notfound.logistics.common.api.ApiErrorResponse;
import com.notfound.logistics.common.api.ApiResponse;
import com.notfound.logistics.common.api.PageResponse;
import com.notfound.logistics.hub.application.dto.hubroute.HubRouteResult;
import com.notfound.logistics.hub.application.service.hubroute.HubRouteQueryService;
import com.notfound.logistics.hub.presentation.dto.hubroute.HubRouteResponse;
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
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "HubRoute", description = "허브 간 이동경로 조회 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/hub-routes")
public class HubRouteController {

  private final HubRouteQueryService hubRouteQueryService;

  @Operation(summary = "허브 간 이동경로 목록 조회", description = "출발 허브와 도착 허브 ID를 조건으로 이동경로를 검색합니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "조회 성공",
        useReturnTypeSchema = true),
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
  @GetMapping
  public ApiResponse<PageResponse<HubRouteResponse>> getRoutes(
      @Parameter(description = "출발 허브 ID")
          @RequestParam(name = "departure_hub_id", required = false)
          UUID departureHubId,
      @Parameter(description = "도착 허브 ID") @RequestParam(name = "arrival_hub_id", required = false)
          UUID arrivalHubId,
      @Parameter(description = "페이지 번호 (0부터 시작)") @RequestParam(defaultValue = "0") @Min(0)
          int page,
      @Parameter(description = "페이지 크기 (1~100)")
          @RequestParam(defaultValue = "20")
          @Min(1)
          @Max(100)
          int size,
      @Parameter(description = "정렬: created_at,desc(기본) 또는 id,asc 등")
          @RequestParam(required = false)
          @Pattern(regexp = "(?i)(created_at|id),(asc|desc)")
          String sort) {

    Page<HubRouteResult> result =
        hubRouteQueryService.getRoutes(departureHubId, arrivalHubId, page, size, sort);

    return new ApiResponse<>(
        new PageResponse<>(
            result.getContent().stream().map(HubRouteResponse::from).toList(),
            result.getNumber(),
            result.getSize(),
            result.getTotalElements()));
  }
}
