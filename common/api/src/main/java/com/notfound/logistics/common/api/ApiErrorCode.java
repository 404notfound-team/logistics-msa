package com.notfound.logistics.common.api;

/** Implement this contract in each service's domain-specific error enumeration. */
public interface ApiErrorCode {
  String code();

  String message();

  int httpStatus();
}
