package com.notfound.logistics.common.api;

import java.util.Objects;

/** Successful JSON response. HTTP 204 responses have no body and do not use this type. */
public record ApiResponse<T>(T data) {
  public ApiResponse {
    Objects.requireNonNull(data, "data");
  }
}
