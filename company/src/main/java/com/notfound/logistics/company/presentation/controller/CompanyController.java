package com.notfound.logistics.company.presentation.controller;

import com.notfound.logistics.common.api.ApiResponse;
import com.notfound.logistics.company.application.service.CompanyService;
import com.notfound.logistics.company.domain.model.Company;
import com.notfound.logistics.company.presentation.dto.CompanyCreateRequest;
import com.notfound.logistics.company.presentation.dto.CompanyResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Swagger의 ApiResponse는 공통 응답 타입 ApiResponse와 이름이 같아 전체 패키지 이름으로 쓴다.
@Tag(name = "Company", description = "업체 API")
@RestController
@RequestMapping("/api/companies")
@RequiredArgsConstructor
public class CompanyController {
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
        description = "요청 값 검증 실패 (필수 값 누락, 길이 초과, UUID 형식 오류)")
  })
  @PostMapping
  public ResponseEntity<ApiResponse<CompanyResponse>> createCompany(
      @Valid @RequestBody CompanyCreateRequest request) {
    Company company =
        companyService.createCompany(
            request.name(), request.hubId(), request.address(), request.managerUserId());
    CompanyResponse response = CompanyResponse.from(company);
    // 이 서비스는 항상 Gateway를 거쳐 호출되므로 Location은 Gateway 기준 상대 경로로 준다.
    URI location = URI.create("/api/companies/" + response.companyId());
    return ResponseEntity.created(location).body(new ApiResponse<>(response));
  }
}
