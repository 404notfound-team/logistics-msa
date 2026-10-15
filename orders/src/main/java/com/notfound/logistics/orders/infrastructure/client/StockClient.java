package com.notfound.logistics.orders.infrastructure.client;

import com.notfound.logistics.common.api.ApiResponse;
import com.notfound.logistics.orders.infrastructure.client.OrderClientTypes.Deduction;
import com.notfound.logistics.orders.infrastructure.client.OrderClientTypes.Restoration;
import com.notfound.logistics.orders.infrastructure.client.OrderClientTypes.StockOperation;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(
    name = "hub",
    contextId = "ordersStock",
    url = "${orders.clients.stock-url:}",
    configuration = OrderFeignConfiguration.class)
public interface StockClient {
  @GetMapping("/internal/stocks/operations/{id}")
  ApiResponse<StockOperation> operation(@PathVariable("id") UUID id);

  @PostMapping("/internal/stocks/deductions")
  ApiResponse<StockOperation> deduct(@RequestBody Deduction request);

  @PostMapping("/internal/stocks/restorations")
  ApiResponse<StockOperation> restore(@RequestBody Restoration request);
}
