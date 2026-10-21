package com.notfound.logistics.orders.infrastructure.client;

import com.notfound.logistics.common.api.ApiResponse;
import com.notfound.logistics.orders.infrastructure.client.OrderClientTypes.Company;
import com.notfound.logistics.orders.infrastructure.client.OrderClientTypes.Product;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(
    name = "company",
    contextId = "ordersCompany",
    url = "${orders.clients.company-url:}",
    configuration = OrderFeignConfiguration.class)
public interface CompanyClient {
  @GetMapping("/internal/companies/{id}")
  ApiResponse<Company> company(@PathVariable("id") UUID id);

  @GetMapping("/internal/products/{id}")
  ApiResponse<Product> product(@PathVariable("id") UUID id);
}
