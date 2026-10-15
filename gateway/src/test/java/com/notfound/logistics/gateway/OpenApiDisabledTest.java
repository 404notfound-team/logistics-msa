package com.notfound.logistics.gateway;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

@ActiveProfiles("test")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "JWT_SECRET=test-only-signing-key-for-gateway-module-tests-32bytes",
      "eureka.client.enabled=false",
      "management.tracing.enabled=false"
    })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OpenApiDisabledTest {
  @LocalServerPort private int port;

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/swagger-ui.html",
        "/swagger-ui/index.html",
        "/webjars/swagger-ui/index.html",
        "/v3/api-docs",
        "/v3/api-docs/swagger-config",
        "/v3/api-docs/users"
      })
  void documentationIsUnavailableOutsideLocalProfile(String path) {
    WebTestClient.bindToServer()
        .baseUrl("http://127.0.0.1:" + port)
        .build()
        .get()
        .uri(path)
        .exchange()
        .expectStatus()
        .isNotFound();
  }
}
