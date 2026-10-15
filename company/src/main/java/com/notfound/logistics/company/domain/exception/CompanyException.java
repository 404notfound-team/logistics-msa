package com.notfound.logistics.company.domain.exception;

public class CompanyException extends RuntimeException {
  private final CompanyError error;

  public CompanyException(CompanyError error) {
    super(error.name());
    this.error = error;
  }

  public CompanyError getError() {
    return error;
  }
}
