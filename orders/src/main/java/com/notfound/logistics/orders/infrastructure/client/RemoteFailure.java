package com.notfound.logistics.orders.infrastructure.client;

public class RemoteFailure extends RuntimeException {
  private final int status;
  private final String code;

  public RemoteFailure(int status, String code) {
    super("External order operation failed");
    this.status = status;
    this.code = code;
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }
}
