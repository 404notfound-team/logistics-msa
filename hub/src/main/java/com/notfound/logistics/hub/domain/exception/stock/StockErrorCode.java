package com.notfound.logistics.hub.domain.exception.stock;

public enum StockErrorCode {
  STOCK_NOT_FOUND("재고를 찾을 수 없습니다."),
  INVALID_STOCK_QUANTITY("재고 변경 수량은 0보다 커야 합니다."),
  INVALID_INITIAL_STOCK("초기 재고는 0 이상이어야 합니다."),
  INSUFFICIENT_STOCK("재고가 부족합니다."),
  STOCK_ALREADY_EXISTS("해당 허브에 동일한 상품의 재고가 이미 존재합니다."),
  STOCK_QUANTITY_OVERFLOW("재고 수량이 허용 범위를 초과했습니다.");

  private final String description;

  StockErrorCode(String description) {
    this.description = description;
  }

  public String code() {
    return name();
  }

  public String message() {
    return description;
  }
}
