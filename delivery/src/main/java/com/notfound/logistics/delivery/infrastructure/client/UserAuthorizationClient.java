package com.notfound.logistics.delivery.infrastructure.client;

import com.notfound.logistics.common.api.ApiResponse;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** 배정할 계정이 현재 활성 배송 담당자인지 Users에서 확인한다. */
@FeignClient(
    name = "users",
    contextId = "deliveryUsers",
    url = "${delivery.clients.users-url:}",
    configuration = InternalServiceFeignConfiguration.class)
public interface UserAuthorizationClient {
  @GetMapping("/internal/users/{id}/authorization")
  ApiResponse<Authorization> getAuthorization(@PathVariable("id") UUID id);

  record Authorization(UUID userId, Boolean active, Boolean isDeliveryStaff) {}
}
