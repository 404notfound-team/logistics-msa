package com.notfound.logistics.orders.infrastructure.client;

import com.notfound.logistics.common.api.ApiResponse;
import com.notfound.logistics.orders.infrastructure.client.OrderClientTypes.Deduction;
import com.notfound.logistics.orders.infrastructure.client.OrderClientTypes.Stock;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(
    name = "hub",
    contextId = "ordersStock",
    url = "${orders.clients.stock-url:}",
    configuration = OrderFeignConfiguration.class)
public interface StockClient {
  @PostMapping("/internal/stocks/deductions")
  ApiResponse<Stock> deduct(@RequestBody Deduction request);
}
