package com.notfound.logistics.hub.domain.hub.presentation.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.notfound.logistics.common.api.ApiErrorResponse;
import com.notfound.logistics.hub.domain.hub.domain.exception.HubBusinessException;
import com.notfound.logistics.hub.domain.hub.domain.exception.HubErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class HubExceptionHandlerTest {

  private final HubExceptionHandler handler = new HubExceptionHandler();

  @Test
  void hubNotFoundUsesSharedErrorContract() {
    ResponseEntity<ApiErrorResponse> response =
        handler.handleHubBusinessException(new HubBusinessException(HubErrorCode.HUB_NOT_FOUND));

    assertThat(response.getStatusCode().value()).isEqualTo(404);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo("HUB_NOT_FOUND");
    assertThat(response.getBody().fieldErrors()).isEmpty();
  }
}
