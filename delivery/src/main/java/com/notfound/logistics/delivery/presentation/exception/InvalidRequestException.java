package com.notfound.logistics.delivery.presentation.exception;

/** 형식은 맞지만 허용하지 않는 요청 값. field는 외부(snake_case) 필드 경로다. */
public class InvalidRequestException extends RuntimeException {
  private final String field;

  public InvalidRequestException(String field, String message) {
    super(message);
    this.field = field;
  }

  public String field() {
    return field;
  }
}
