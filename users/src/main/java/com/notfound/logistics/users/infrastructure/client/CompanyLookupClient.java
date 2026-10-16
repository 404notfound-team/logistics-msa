package com.notfound.logistics.users.infrastructure.client;

import com.notfound.logistics.common.api.ApiResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(
    name = "company",
    contextId = "usersCompanyLookup",
    configuration = InternalServiceFeignConfiguration.class)
public interface CompanyLookupClient {
  @GetMapping("/internal/companies")
  ApiResponse<List<CompanyLookupResponse>> findByName(@RequestParam("name") String name);

  /** 존재 여부 확인용입니다. 없거나 삭제된 대상은 404입니다. */
  @GetMapping("/internal/companies/{id}")
  ApiResponse<AffiliationResponse> findById(@PathVariable("id") UUID id);

  record CompanyLookupResponse(UUID id, String name, UUID hubId) {}

  record AffiliationResponse(UUID id) {}
}
