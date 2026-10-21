package com.notfound.logistics.hub.infrastructure.client;

import com.notfound.logistics.common.api.ApiResponse;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(
    name = "company",
    contextId = "hubProducts",
    url = "${hub.clients.company-url:}",
    configuration = CompanyFeignConfiguration.class)
public interface CompanyProductClient {
  @GetMapping("/internal/products/{product_id}")
  ApiResponse<ProductResponse> getProduct(@PathVariable("product_id") UUID productId);

  record ProductResponse(UUID id, UUID companyId, String name, Long price) {}
}
