package com.notfound.logistics.orders.domain.exception;

public enum OrderErrorCode {
  ORDER_NOT_FOUND("주문을 찾을 수 없습니다."),
  ORDER_STATE_CONFLICT("현재 주문 상태에서는 요청을 처리할 수 없습니다."),
  INVALID_ORDER_INPUT("주문 정보가 올바르지 않습니다."),
  AUTHENTICATION_REQUIRED("로그인이 필요합니다."),
  ORDER_FORBIDDEN("주문에 접근할 권한이 없습니다."),
  COMPANY_NOT_FOUND("업체를 찾을 수 없습니다."),
  PRODUCT_NOT_FOUND("상품을 찾을 수 없습니다."),
  PRODUCT_SUPPLIER_MISMATCH("상품이 해당 공급업체에 속하지 않습니다."),
  IDEMPOTENCY_KEY_REUSED("같은 멱등 키로 다른 주문을 요청할 수 없습니다."),
  INSUFFICIENT_STOCK("재고가 부족합니다."),
  STOCK_NOT_FOUND("재고를 찾을 수 없습니다."),
  LEGACY_STOCK_CONTRACT("기존 주문의 차감 이력이 없어 자동 재고 처리가 제한됩니다."),
  DELIVERY_CREATION_FAILED("배송 생성에 실패했습니다."),
  UPSTREAM_UNAVAILABLE("연동 서비스의 처리 결과를 확인할 수 없습니다.");

  private final String message;

  OrderErrorCode(String message) {
    this.message = message;
  }

  public String message() {
    return message;
  }
}
