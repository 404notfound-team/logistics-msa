package com.notfound.logistics.hub.domain.hub.presentation;

import com.notfound.logistics.common.api.ApiResponse;
import com.notfound.logistics.common.api.PageResponse;
import com.notfound.logistics.hub.domain.hub.application.HubQueryService;
import com.notfound.logistics.hub.domain.hub.application.dto.HubResult;
import com.notfound.logistics.hub.domain.hub.presentation.dto.HubResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Hub", description = "허브 조회 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/hubs")
public class HubController {

  private final HubQueryService hubQueryService;

  @Operation(summary = "허브 목록 조회", description = "등록된 허브 목록을 조회합니다.")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공")
  @GetMapping
  public ApiResponse<PageResponse<HubResponse>> getHubs(
      @Parameter(description = "페이지 번호 (0부터 시작)") @RequestParam(defaultValue = "0") @Min(0)
          int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
      @RequestParam(defaultValue = "desc") @Pattern(regexp = "(?i)asc|desc") String direction) {

    Page<HubResult> result = hubQueryService.getHubs(page, size, direction);

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
        description = "조회 성공"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "잘못된 허브 ID 형식"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "허브를 찾을 수 없음")
  })
  @GetMapping("/{id}")
  public ApiResponse<HubResponse> getHub(@PathVariable UUID id) {

    HubResponse response = HubResponse.from(hubQueryService.getHub(id));

    return new ApiResponse<>(response);
  }
}
