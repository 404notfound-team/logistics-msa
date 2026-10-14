package com.notfound.logistics.hub.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.notfound.logistics.common.api.ApiErrorResponse;
import com.notfound.logistics.common.api.CommonErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class GlobalExceptionHandlerTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @Test
  void hubNotFoundUsesSharedErrorContract() {
    ResponseEntity<ApiErrorResponse> response =
        handler.business(new HubBusinessException(HubErrorCode.HUB_NOT_FOUND));

    assertThat(response.getStatusCode().value()).isEqualTo(404);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo("HUB_NOT_FOUND");
    assertThat(response.getBody().fieldErrors()).isEmpty();
  }

  @Test
  void unexpectedErrorHidesInternalDetails() {
    ResponseEntity<ApiErrorResponse> response =
        handler.unexpected(new IllegalStateException("database secret"));

    assertThat(response.getStatusCode().value()).isEqualTo(500);
    assertThat(response.getBody()).isNotNull();

    assertThat(response.getBody().code()).isEqualTo(CommonErrorCode.INTERNAL_ERROR.code());

    assertThat(response.getBody().message()).doesNotContain("database secret");
  }
}
