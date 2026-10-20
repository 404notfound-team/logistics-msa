package com.notfound.logistics.orders.application.dto;

import com.notfound.logistics.orders.domain.model.Order;
import com.notfound.logistics.orders.domain.model.OrderStatus;
import com.notfound.logistics.orders.domain.model.RecoveryPhase;
import java.time.Instant;
import java.util.UUID;

public record OrderRecoveryResult(
    UUID orderId,
    OrderStatus status,
    RecoveryPhase recoveryPhase,
    int recoveryAttempts,
    boolean recoveryBlocked,
    String lastError,
    Instant updatedAt) {
  public static OrderRecoveryResult from(Order order) {
    return new OrderRecoveryResult(
        order.getId(),
        order.getStatus(),
        order.getRecoveryPhase(),
        order.getRecoveryAttempts(),
        order.isRecoveryBlocked(),
        order.getLastError(),
        order.getUpdatedAt());
  }
}
