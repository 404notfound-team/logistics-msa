package com.notfound.logistics.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class UntrustedHeaderRemovalFilterTest {
  private final UntrustedHeaderRemovalFilter filter = new UntrustedHeaderRemovalFilter();

  @Test
  void removesServiceAndUserIdentityHeadersBeforeForwarding() {
    var exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer token")
                .header("X-Service-Token", "forged")
                .header("x-actor-username", "admin")
                .header("X-User-Id", "4c7c4c0f-94fb-40d2-b467-5ccaecc9de3e")
                .header("X-User-Role", "MASTER")
                .header("X-Request-Id", "keep"));
    AtomicReference<HttpHeaders> forwarded = new AtomicReference<>();

    filter
        .filter(
            exchange,
            mutated -> {
              forwarded.set(mutated.getRequest().getHeaders());
              return Mono.empty();
            })
        .block();

    HttpHeaders headers = forwarded.get();
    assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer token");
    assertThat(headers.getFirst("X-Request-Id")).isEqualTo("keep");
    assertThat(headers)
        .doesNotContainKeys("X-Service-Token", "X-Actor-Username", "X-User-Id", "X-User-Role");
  }
}
