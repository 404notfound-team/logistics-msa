package com.notfound.logistics.company.presentation;

import com.notfound.logistics.common.api.ApiResponse;
import com.notfound.logistics.company.application.CompanyService;
import com.notfound.logistics.company.presentation.dto.CompanyCreateRequest;
import com.notfound.logistics.company.presentation.dto.CompanyResponse;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/companies")
public class CompanyController {
  private final CompanyService companyService;

  public CompanyController(CompanyService companyService) {
    this.companyService = companyService;
  }

  @PostMapping
  public ResponseEntity<ApiResponse<CompanyResponse>> createCompany(
      @Valid @RequestBody CompanyCreateRequest request) {
    CompanyResponse response = companyService.createCompany(request);
    // Relative to the Gateway, since this service is never addressed directly.
    URI location = URI.create("/api/companies/" + response.companyId());
    return ResponseEntity.created(location).body(new ApiResponse<>(response));
  }
}
