package com.notfound.logistics.orders.infrastructure.client;

import com.notfound.logistics.common.api.ApiResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(
    name = "users",
    contextId = "ordersUsers",
    url = "${orders.clients.users-url:}",
    configuration = OrderFeignConfiguration.class)
public interface UserIdentityClient {
  @GetMapping("/internal/users/identities")
  ApiResponse<List<Identity>> identities(
      @RequestParam("username") String username,
      @RequestParam(name = "user_id", required = false) UUID assigneeId);

  record Identity(UUID id, String username, String name) {}
}
