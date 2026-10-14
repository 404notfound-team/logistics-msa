package com.notfound.logistics.common.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Objects;

public record ApiErrorResponse(
    String code,
    String message,
    @JsonProperty("trace_id") @Schema(nullable = true) String traceId,
    @JsonProperty("field_errors") List<FieldViolation> fieldErrors) {
  public ApiErrorResponse {
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(message, "message");
    fieldErrors = List.copyOf(fieldErrors);
  }

  public static ApiErrorResponse of(ApiErrorCode error, String traceId) {
    return new ApiErrorResponse(error.code(), error.message(), traceId, List.of());
  }

  public static ApiErrorResponse validation(String traceId, List<FieldViolation> fieldErrors) {
    return new ApiErrorResponse(
        CommonErrorCode.INVALID_REQUEST.code(),
        CommonErrorCode.INVALID_REQUEST.message(),
        traceId,
        fieldErrors);
  }
}
