package com.notfound.logistics.orders.infra;

import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;

@Profile("local")
@FeignClient(name = "hub", contextId = "infraHubProbe")
interface HubProbeClient {
    @GetMapping("/internal/infra/ping")
    Map<String, String> ping();
}
