package com.notfound.logistics.delivery.presentation;

/** 형식은 맞지만 허용하지 않는 요청 값. field는 외부(snake_case) 필드 경로다. */
class InvalidRequestException extends RuntimeException {
  private final String field;

  InvalidRequestException(String field, String message) {
    super(message);
    this.field = field;
  }

  String field() {
    return field;
  }
}
