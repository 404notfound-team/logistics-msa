package com.notfound.logistics.orders.presentation.dto;

import com.notfound.logistics.orders.application.dto.OrderRecoveryResult;
import com.notfound.logistics.orders.domain.model.OrderStatus;
import com.notfound.logistics.orders.domain.model.RecoveryPhase;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "MASTER 전용 주문 복구 상태. 재개 요청은 이 조회의 시각·단계·실패 횟수를 사용")
public record OrderRecoveryResponse(
    UUID orderId,
    OrderStatus status,
    RecoveryPhase recoveryPhase,
    int recoveryAttempts,
    boolean recoveryBlocked,
    String lastError,
    Instant updatedAt) {
  public static OrderRecoveryResponse from(OrderRecoveryResult value) {
    return new OrderRecoveryResponse(
        value.orderId(),
        value.status(),
        value.recoveryPhase(),
        value.recoveryAttempts(),
        value.recoveryBlocked(),
        value.lastError(),
        value.updatedAt());
  }
}
