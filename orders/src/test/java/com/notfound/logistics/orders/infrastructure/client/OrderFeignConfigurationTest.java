package com.notfound.logistics.orders.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notfound.logistics.orders.infrastructure.audit.OrdersAuditorAware;
import com.notfound.logistics.orders.support.OrderClientFixture;
import feign.Request;
import feign.RequestTemplate;
import feign.Response;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OrderFeignConfigurationTest {
  private final OrderFeignConfiguration configuration = new OrderFeignConfiguration();

  @Test
  void persistedRecoveryActorAndServiceTokenAreSentWithoutUserBearer() {
    var auditor = new OrdersAuditorAware();
    var interceptor = configuration.orderRequestHeaders(auditor, OrderClientFixture.SERVICE_TOKEN);
    var template = new RequestTemplate();
    auditor.runAsPersistedUser("original-user", () -> interceptor.apply(template));
    assertThat(template.headers().get("X-Service-Token"))
        .containsExactly(OrderClientFixture.SERVICE_TOKEN);
    assertThat(template.headers().get("X-Actor-Username")).containsExactly("original-user");
    assertThat(template.headers()).doesNotContainKey("Authorization");
  }

  @Test
  void errorDecoderKeepsOnlyRecognizedCodesAndNeverAnExternalMessage() {
    var decoder = configuration.orderErrorDecoder(new ObjectMapper());
    for (String body : List.of("not-json", "{\"code\":\"secret-value\"}", "{}")) {
      var error = (RemoteFailure) decoder.decode("client", response(503, body));
      assertThat(error.status()).isEqualTo(503);
      assertThat(error.code()).isEqualTo("UPSTREAM_UNAVAILABLE");
      assertThat(error.getMessage()).doesNotContain(body);
    }
    var error =
        (RemoteFailure)
            decoder.decode(
                "client",
                response(409, "{\"code\":\"DELIVERY_ALREADY_STARTED\",\"message\":\"private\"}"));
    assertThat(error.code()).isEqualTo("DELIVERY_ALREADY_STARTED");
    assertThat(error.getMessage()).doesNotContain("private");
  }

  private Response response(int status, String body) {
    return Response.builder()
        .status(status)
        .reason("test")
        .headers(Map.of())
        .request(
            Request.create(
                Request.HttpMethod.GET,
                "http://test.invalid",
                Map.of(),
                null,
                StandardCharsets.UTF_8,
                null))
        .body(body, StandardCharsets.UTF_8)
        .build();
  }
}
