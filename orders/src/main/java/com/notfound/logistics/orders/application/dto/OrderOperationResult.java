package com.notfound.logistics.orders.application.dto;

import com.notfound.logistics.orders.domain.exception.OrderErrorCode;

public record OrderOperationResult(OrderResult order, Outcome outcome, OrderErrorCode error) {
  public enum Outcome {
    CREATED,
    RETURNED,
    IN_PROGRESS,
    REJECTED
  }
}
