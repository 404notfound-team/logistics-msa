package com.notfound.logistics.company.presentation.controller;

import com.notfound.logistics.common.api.ApiResponse;
import com.notfound.logistics.company.application.service.CompanyService;
import com.notfound.logistics.company.presentation.dto.CompanyCreateRequest;
import com.notfound.logistics.company.presentation.dto.CompanyResponse;
import jakarta.validation.Valid;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/companies")
@RequiredArgsConstructor
public class CompanyController {
  private final CompanyService companyService;

  @PostMapping
  public ResponseEntity<ApiResponse<CompanyResponse>> createCompany(
      @Valid @RequestBody CompanyCreateRequest request) {
    CompanyResponse response = companyService.createCompany(request);
    // 이 서비스는 항상 Gateway를 거쳐 호출되므로 Location은 Gateway 기준 상대 경로로 준다.
    URI location = URI.create("/api/companies/" + response.companyId());
    return ResponseEntity.created(location).body(new ApiResponse<>(response));
  }
}
