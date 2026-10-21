package com.notfound.logistics.orders.domain.model;

/** 최근 오류 사유와 별개로 저장하는 처리 재개 단계다. */
public enum RecoveryPhase {
  STOCK_DEDUCTION,
  DELIVERY_CREATION,
  DELIVERY_CANCELLATION,
  STOCK_RESTORATION,
  STOCK_COMPENSATION
}
