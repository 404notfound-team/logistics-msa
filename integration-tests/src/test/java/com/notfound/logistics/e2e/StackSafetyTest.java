package com.notfound.logistics.e2e;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StackSafetyTest {
  @ParameterizedTest
  @ValueSource(
      strings = {"", "logistics-msa", "another-existing-stack", "logistics-orders-recovery-"})
  void rejectsSharedOrUnspecifiedProjects(String project) {
    assertThatThrownBy(() -> ComposeStack.validateProject(project))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://127.0.0.1:8080",
        "https://localhost:18085",
        "http://example.com:18085",
        "http://user:pass@localhost:18085",
        "http://localhost:18085/",
        "http://localhost:18085?target=other",
        "http://localhost:18085#other"
      })
  void rejectsGatewayOutsideSelectedStack(String gateway) {
    assertThatThrownBy(() -> ComposeStack.validateGateway(URI.create(gateway), 18085))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void acceptsOnlyMatchingLocalGateway() {
    assertThatCode(() -> ComposeStack.validateGateway(URI.create("http://localhost:18085"), 18085))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"code\":\"NOT_FOUND\"}\n404",
        "{\"code\":\"UNAUTHORIZED\"}\n401",
        "{\"code\":\"METHOD_NOT_ALLOWED\"}\n405"
      })
  void rejectsMissingOrUnauthenticatedInternalApiBeforeOrdersAreCreated(String response) {
    assertThatThrownBy(() -> ComposeStack.verifyDeliveryProbe("/cancel", response, 404))
        .isInstanceOf(AssertionError.class);
  }

  @Test
  void acceptsBusinessErrorsFromAllInternalApiProbesAndEmptyDeletes() throws Exception {
    ComposeStack.verifyDeliveryProbe("lookup", "{\"code\":\"DELIVERY_NOT_FOUND\"}\n404", 404);
    ComposeStack.verifyDeliveryProbe("create", "{\"code\":\"INVALID_REQUEST\"}\n400", 400);
    GatewayClient.expect(new GatewayClient.Response(204, GatewayClient.JSON.nullNode()), 204);
  }
}
