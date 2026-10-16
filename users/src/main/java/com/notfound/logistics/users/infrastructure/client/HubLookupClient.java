package com.notfound.logistics.users.infrastructure.client;

import com.notfound.logistics.common.api.ApiResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(
    name = "hub",
    contextId = "usersHubLookup",
    configuration = InternalServiceFeignConfiguration.class)
public interface HubLookupClient {
  @GetMapping("/internal/hubs")
  ApiResponse<List<HubLookupResponse>> findByName(@RequestParam("name") String name);

  record HubLookupResponse(UUID id, String name) {}
}
