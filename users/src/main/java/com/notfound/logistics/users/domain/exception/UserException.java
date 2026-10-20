package com.notfound.logistics.users.domain.exception;

public class UserException extends RuntimeException {
  private final UserError error;

  public UserException(UserError error) {
    super(error.name());
    this.error = error;
  }

  public UserError getError() {
    return error;
  }
}
