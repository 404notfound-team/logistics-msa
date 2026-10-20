package com.notfound.logistics.orders.presentation.dto;

import com.notfound.logistics.orders.application.dto.ResumeOrderRecoveryCommand;
import com.notfound.logistics.orders.domain.model.RecoveryPhase;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;

@Schema(description = "운영자가 원격 결과와 장애 원인을 확인한 차단 주문의 1회 재개 요청")
public record ResumeOrderRecoveryRequest(
    @NotNull Instant expectedUpdatedAt,
    @NotNull RecoveryPhase expectedPhase,
    @NotNull @PositiveOrZero Integer expectedAttempts,
    @NotNull @AssertTrue Boolean upstreamChecked) {
  public ResumeOrderRecoveryCommand toCommand() {
    return new ResumeOrderRecoveryCommand(expectedUpdatedAt, expectedPhase, expectedAttempts);
  }
}
