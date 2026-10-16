package com.notfound.logistics.hub.infrastructure.client;

/** 상품의 존재 여부를 확정할 수 없는 연동 실패. 응답에 외부 오류 본문을 노출하지 않는다. */
public class ProductLookupException extends RuntimeException {
  public ProductLookupException() {
    super("Product lookup unavailable");
  }

  public ProductLookupException(Throwable cause) {
    super("Product lookup unavailable", cause);
  }
}
