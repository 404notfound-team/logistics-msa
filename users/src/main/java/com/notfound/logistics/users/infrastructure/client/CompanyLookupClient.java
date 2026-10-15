package com.notfound.logistics.users.infrastructure.client;

import java.util.List;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "company", contextId = "usersCompanyLookup")
public interface CompanyLookupClient {
  @GetMapping("/internal/companies")
  List<CompanyLookupResponse> findByName(@RequestParam("name") String name);

  record CompanyLookupResponse(UUID id, String name, UUID hubId) {}
}
