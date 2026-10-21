package com.notfound.logistics.common.api;

import java.util.Objects;

/** Public field path and safe validation message; never carries the rejected value. */
public record FieldViolation(String field, String message) {
  public FieldViolation {
    Objects.requireNonNull(field, "field");
    Objects.requireNonNull(message, "message");
  }
}
