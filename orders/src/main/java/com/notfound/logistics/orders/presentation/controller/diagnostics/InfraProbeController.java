package com.notfound.logistics.orders.presentation.controller.diagnostics;

import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("local")
@RestController
class InfraProbeController {
  @GetMapping("/internal/infra/ping")
  Map<String, String> ping() {
    return Map.of("service", "orders", "status", "UP");
  }
}
