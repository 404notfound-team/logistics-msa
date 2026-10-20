package com.notfound.logistics.orders.presentation.controller.infra;

import com.notfound.logistics.orders.infrastructure.client.HubProbeClient;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("local")
@RestController
class InfraTraceController {
  private final HubProbeClient hub;

  InfraTraceController(HubProbeClient hub) {
    this.hub = hub;
  }

  @GetMapping("/internal/infra/trace")
  Map<String, Object> trace() {
    return Map.of("service", "orders", "downstream", hub.ping());
  }
}
