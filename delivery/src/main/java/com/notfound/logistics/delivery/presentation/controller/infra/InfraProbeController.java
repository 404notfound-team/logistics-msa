package com.notfound.logistics.delivery.presentation.controller.infra;

import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("local")
@RestController
class InfraProbeController {
  @GetMapping("/internal/infra/ping")
  Map<String, String> ping() {
    return Map.of("service", "delivery", "status", "UP");
  }
}
