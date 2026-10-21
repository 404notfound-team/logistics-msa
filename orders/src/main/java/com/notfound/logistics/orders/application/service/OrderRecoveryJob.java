package com.notfound.logistics.orders.application.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "orders.recovery.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class OrderRecoveryJob {
  private final OrderService service;

  @Scheduled(fixedDelayString = "${orders.recovery.delay-ms:5000}")
  public void recover() {
    service.recover();
  }
}
