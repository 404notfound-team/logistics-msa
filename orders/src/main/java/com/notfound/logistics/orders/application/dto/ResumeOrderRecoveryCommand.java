package com.notfound.logistics.orders.application.dto;

import com.notfound.logistics.orders.domain.model.RecoveryPhase;
import java.time.Instant;

public record ResumeOrderRecoveryCommand(
    Instant expectedUpdatedAt, RecoveryPhase expectedPhase, int expectedAttempts) {}
