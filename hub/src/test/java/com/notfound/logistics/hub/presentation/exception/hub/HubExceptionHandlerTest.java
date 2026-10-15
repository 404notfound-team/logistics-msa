package com.notfound.logistics.hub.presentation.exception.hub;

import static org.assertj.core.api.Assertions.assertThat;

import com.notfound.logistics.common.api.ApiErrorResponse;
import com.notfound.logistics.hub.domain.exception.hub.HubBusinessException;
import com.notfound.logistics.hub.domain.exception.hub.HubErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class HubExceptionHandlerTest {

  private final HubExceptionHandler handler = new HubExceptionHandler();

  @Test
  void insufficientStockUsesConflictResponse() {
    ResponseEntity<ApiErrorResponse> response =
        handler.handleHubBusinessException(
            new HubBusinessException(HubErrorCode.INSUFFICIENT_STOCK));

    assertThat(response.getStatusCode().value()).isEqualTo(409);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo("INSUFFICIENT_STOCK");
  }

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
