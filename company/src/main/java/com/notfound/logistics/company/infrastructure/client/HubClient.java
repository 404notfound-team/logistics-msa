package com.notfound.logistics.company.infrastructure.client;

import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

// 응답 본문은 쓰지 않고 상태 코드로만 존재 여부를 판단한다(200 존재, 404 없음).
@FeignClient(
    name = "hub",
    contextId = "companyHub",
    url = "${company.clients.hub-url:}",
    configuration = HubFeignConfiguration.class)
interface HubClient {
  @GetMapping("/internal/hubs/{hub_id}")
  void getHub(@PathVariable("hub_id") UUID hubId);
}
