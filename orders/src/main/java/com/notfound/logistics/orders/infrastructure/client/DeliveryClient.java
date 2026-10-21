package com.notfound.logistics.orders.infrastructure.client;

import com.notfound.logistics.common.api.ApiResponse;
import com.notfound.logistics.orders.infrastructure.client.OrderClientTypes.Delivery;
import com.notfound.logistics.orders.infrastructure.client.OrderClientTypes.DeliveryRequest;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(
    name = "delivery",
    contextId = "ordersDelivery",
    url = "${orders.clients.delivery-url:}",
    configuration = OrderFeignConfiguration.class)
public interface DeliveryClient {
  @GetMapping("/internal/deliveries/by-order/{id}")
  ApiResponse<Delivery> byOrder(@PathVariable("id") UUID id);

  @PostMapping("/internal/deliveries")
  ApiResponse<Delivery> create(@RequestBody DeliveryRequest request);

  @PostMapping("/internal/deliveries/{id}/cancel")
  ApiResponse<Delivery> cancel(@PathVariable("id") UUID id);

  @DeleteMapping("/internal/deliveries/{id}")
  void delete(@PathVariable("id") UUID id);
}
