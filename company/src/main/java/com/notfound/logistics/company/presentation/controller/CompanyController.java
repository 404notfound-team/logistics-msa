package com.notfound.logistics.company.presentation.controller;

import com.notfound.logistics.common.api.ApiErrorResponse;
import com.notfound.logistics.common.api.ApiResponse;
import com.notfound.logistics.common.api.PageResponse;
import com.notfound.logistics.company.application.service.CompanyService;
import com.notfound.logistics.company.domain.model.Company;
import com.notfound.logistics.company.presentation.dto.CompanyCreateRequest;
import com.notfound.logistics.company.presentation.dto.CompanyResponse;
import com.notfound.logistics.company.presentation.dto.CompanyUpdateRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
                          "{\"code\": \"UNAUTHORIZED\", \"message\": \"인증이 필요합니다.\", \"trace_id\": null, \"field_errors\": []}")))
})
@Tag(name = "Company", description = "업체 API")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/companies")
@RequiredArgsConstructor
public class CompanyController {
  // 공통 계약: 기본 정렬은 created_at 내림차순, 같은 시각이면 PK로 순서를 고정한다.
  private static final Sort DEFAULT_SORT =
      Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.ASC, "id"));

  private final CompanyService companyService;

  // HTTP 요청 DTO는 여기서 값으로 풀어 서비스에 넘기고, 결과 엔티티를 응답 DTO로 변환한다.
  @Operation(
      summary = "업체 생성",
      description = "업체를 생성합니다. 성공 시 Location 헤더로 생성된 업체의 상세 조회 경로를 반환합니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "201",
        description = "생성 성공"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "요청 값 검증 실패 (필수 값 누락, 길이 초과, UUID 형식 오류)",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = @ExampleObject(value = ErrorExamples.CREATE_INVALID_EXAMPLE))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "403",
        description = "MASTER가 아니면서 본인이 아닌 사용자를 관리자로 지정함 (COMPANY_ACCESS_DENIED)",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = @ExampleObject(value = ErrorExamples.ACCESS_DENIED_EXAMPLE))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "허브를 찾을 수 없음 (HUB_NOT_FOUND)",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = @ExampleObject(value = ErrorExamples.HUB_NOT_FOUND_EXAMPLE))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "503",
        description = "허브 확인 실패 또는 Gateway 사용자 확인 실패 (SERVICE_UNAVAILABLE)",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples =
                    @ExampleObject(
                        name = "SERVICE_UNAVAILABLE",
                        value =
                            "{\"code\":\"SERVICE_UNAVAILABLE\",\"message\":\"서비스를 일시적으로 사용할 수 없습니다.\",\"trace_id\":null,\"field_errors\":[]}"))),
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
  public ResponseEntity<ApiResponse<CompanyResponse>> createCompany(
      @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal,
      @Valid @RequestBody CompanyCreateRequest request) {
    Company company =
        companyService.createCompany(
            Requesters.from(principal),
            request.name(),
            request.hubId(),
            request.address(),
            request.managerUserId());
    CompanyResponse response = CompanyResponse.from(company);
    // 이 서비스는 항상 Gateway를 거쳐 호출되므로 Location은 Gateway 기준 상대 경로로 준다.
    URI location = URI.create("/api/companies/" + response.id());
    return ResponseEntity.created(location).body(new ApiResponse<>(response));
  }

  @Operation(summary = "업체 상세 조회", description = "업체 ID로 업체 정보를 조회합니다. 삭제된 업체는 조회되지 않습니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "조회 성공"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "잘못된 업체 ID 형식",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = @ExampleObject(value = ErrorExamples.INVALID_ID_EXAMPLE))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "업체를 찾을 수 없음 (COMPANY_NOT_FOUND)",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = @ExampleObject(value = ErrorExamples.COMPANY_NOT_FOUND_EXAMPLE))),
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
  @GetMapping("/{company_id}")
  public ResponseEntity<ApiResponse<CompanyResponse>> getCompany(
      @Parameter(description = "업체 ID", example = "7d752e7e-788b-485b-808e-fd438dfe8bae")
          @PathVariable("company_id")
          UUID companyId) {
    CompanyResponse response = CompanyResponse.from(companyService.findCompany(companyId));
    return ResponseEntity.ok(new ApiResponse<>(response));
  }

  // 검색 조건은 모두 선택 사항이다. 범위를 벗어난 page·size는 400으로 응답한다.
  @Operation(
      summary = "업체 목록·검색",
      description = "업체명(부분 일치, 대소문자 무시)과 허브 ID로 업체를 검색합니다. 최신 생성순으로 정렬합니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "조회 성공 (결과가 없으면 빈 content)"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "page·size 범위 오류 또는 허브 ID 형식 오류",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = @ExampleObject(value = ErrorExamples.LIST_INVALID_EXAMPLE))),
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
  public ResponseEntity<ApiResponse<PageResponse<CompanyResponse>>> getCompanies(
      @Parameter(description = "업체명 (부분 일치)", example = "물류")
          @RequestParam(name = "name", required = false)
          String name,
      @Parameter(description = "허브 ID", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
          @RequestParam(name = "hub_id", required = false)
          UUID hubId,
      @Parameter(description = "페이지 번호 (0부터 시작)")
          @RequestParam(name = "page", defaultValue = "0")
          @Min(0)
          int page,
      @Parameter(description = "페이지 크기 (1~100)")
          @RequestParam(name = "size", defaultValue = "20")
          @Min(1)
          @Max(100)
          int size) {
    Pageable pageable = PageRequest.of(page, size, DEFAULT_SORT);
    Page<Company> companies = companyService.searchCompanies(name, hubId, pageable);
    PageResponse<CompanyResponse> response =
        new PageResponse<>(
            companies.getContent().stream().map(CompanyResponse::from).toList(),
            companies.getNumber(),
            companies.getSize(),
            companies.getTotalElements());
    return ResponseEntity.ok(new ApiResponse<>(response));
  }

  @Operation(summary = "업체 수정", description = "보낸 필드만 변경합니다. 생략하거나 null인 필드는 기존 값을 유지합니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "수정 성공"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "요청 값 검증 실패 (공백 값, 길이 초과, UUID 형식 오류)",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = @ExampleObject(value = ErrorExamples.UPDATE_INVALID_EXAMPLE))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "업체 또는 변경할 허브를 찾을 수 없음 (COMPANY_NOT_FOUND, HUB_NOT_FOUND)",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = {
                  @ExampleObject(
                      name = "COMPANY_NOT_FOUND",
                      value = ErrorExamples.COMPANY_NOT_FOUND_EXAMPLE),
                  @ExampleObject(
                      name = "HUB_NOT_FOUND",
                      value = ErrorExamples.HUB_NOT_FOUND_EXAMPLE)
                })),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "403",
        description = "MASTER 또는 해당 업체 관리자가 아님 (COMPANY_ACCESS_DENIED)",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = @ExampleObject(value = ErrorExamples.ACCESS_DENIED_EXAMPLE))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "503",
        description = "허브 확인 실패 또는 Gateway 사용자 확인 실패 (SERVICE_UNAVAILABLE)",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples =
                    @ExampleObject(
                        name = "SERVICE_UNAVAILABLE",
                        value =
                            "{\"code\":\"SERVICE_UNAVAILABLE\",\"message\":\"서비스를 일시적으로 사용할 수 없습니다.\",\"trace_id\":null,\"field_errors\":[]}"))),
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
  @PatchMapping("/{company_id}")
  public ResponseEntity<ApiResponse<CompanyResponse>> updateCompany(
      @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal,
      @Parameter(description = "업체 ID", example = "7d752e7e-788b-485b-808e-fd438dfe8bae")
          @PathVariable("company_id")
          UUID companyId,
      @Valid @RequestBody CompanyUpdateRequest request) {
    Company company =
        companyService.updateCompany(
            Requesters.from(principal),
            companyId,
            request.name(),
            request.hubId(),
            request.address(),
            request.managerUserId());
    return ResponseEntity.ok(new ApiResponse<>(CompanyResponse.from(company)));
  }

  @Operation(summary = "업체 논리 삭제", description = "업체를 논리 삭제하고 삭제 시각·작업자를 기록합니다. 삭제된 업체는 조회되지 않습니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "204",
        description = "삭제 성공"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "잘못된 업체 ID 형식",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = @ExampleObject(value = ErrorExamples.INVALID_ID_EXAMPLE))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "업체를 찾을 수 없음 (이미 삭제된 업체 포함)",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = @ExampleObject(value = ErrorExamples.COMPANY_NOT_FOUND_EXAMPLE))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "403",
        description = "MASTER 또는 해당 업체 관리자가 아님 (COMPANY_ACCESS_DENIED)",
        content =
            @Content(
                schema = @Schema(implementation = ApiErrorResponse.class),
                examples = @ExampleObject(value = ErrorExamples.ACCESS_DENIED_EXAMPLE))),
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
  @DeleteMapping("/{company_id}")
  public ResponseEntity<Void> deleteCompany(
      @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal,
      @Parameter(description = "업체 ID", example = "7d752e7e-788b-485b-808e-fd438dfe8bae")
          @PathVariable("company_id")
          UUID companyId) {
    companyService.deleteCompany(Requesters.from(principal), companyId);
    return ResponseEntity.noContent().build();
  }
}
