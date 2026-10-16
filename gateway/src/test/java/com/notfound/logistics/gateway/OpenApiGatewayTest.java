package com.notfound.logistics.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

@ActiveProfiles("local")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "JWT_SECRET=test-only-signing-key-for-gateway-module-tests-32bytes",
      "GATEWAY_SERVICE_TOKEN=test-only-gateway-service-token",
      "eureka.client.enabled=false",
      "spring.cloud.service-registry.auto-registration.enabled=false",
      "management.tracing.enabled=false"
    })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OpenApiGatewayTest {
  private static final Map<String, String> PUBLIC_PATHS =
      Map.of(
          "users", "/api/users/me",
          "company", "/api/companies/openapi-test",
          "hub", "/api/hubs/openapi-test",
          "orders", "/api/orders/openapi-test",
          "delivery", "/api/deliveries/openapi-test");
  private static final Map<String, DisposableServer> DOWNSTREAMS = startDownstreams();

  @LocalServerPort private int port;
  private WebTestClient client;

  @DynamicPropertySource
  static void serviceLocations(DynamicPropertyRegistry registry) {
    DOWNSTREAMS.forEach(
        (service, server) ->
            registry.add(
                "spring.cloud.discovery.client.simple.instances." + service + "[0].uri",
                () -> "http://127.0.0.1:" + server.port()));
  }

  @BeforeEach
  void connectToGateway() {
    client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
  }

  @AfterAll
  static void stopDownstreams() {
    DOWNSTREAMS.values().forEach(DisposableServer::disposeNow);
  }

  @Test
  void swaggerUiListsExactlyTheFiveServices() {
    JsonNode config = getJson("/v3/api-docs/swagger-config");
    Map<String, String> urls = new LinkedHashMap<>();
    config
        .get("urls")
        .forEach(entry -> urls.put(entry.get("name").asText(), entry.get("url").asText()));
    assertThat(urls.keySet()).containsExactlyInAnyOrderElementsOf(PUBLIC_PATHS.keySet());
    urls.forEach((service, url) -> assertThat(url).isEqualTo("/v3/api-docs/" + service));
    assertThat(config.path("urls.primaryName").asText()).isEqualTo("users");
    client.get().uri("/swagger-ui.html").exchange().expectStatus().is3xxRedirection();
    client.get().uri("/swagger-ui/index.html").exchange().expectStatus().isOk();
  }

  @ParameterizedTest
  @ValueSource(strings = {"users", "company", "hub", "orders", "delivery"})
  void selectedDocumentAndItsPublicOperationReachTheCorrectService(String service) {
    JsonNode document = getJson("/v3/api-docs/" + service);
    assertThat(document.path("info").path("title").asText()).isEqualTo(service);
    String server = document.path("servers").get(0).path("url").asText();
    String operation = document.path("paths").fieldNames().next();
    assertThat(server).isEqualTo("/");
    assertThat(operation).isEqualTo(PUBLIC_PATHS.get(service));
    JsonNode response = getJson(server + operation.substring(1));
    assertThat(response.path("service").asText()).isEqualTo(service);
  }

  @Test
  void documentRoutesDoNotExposeOtherServicesOrInternalPaths() {
    for (String path :
        new String[] {
          "/v3/api-docs/eureka", "/v3/api-docs/users/internal/infra/ping", "/internal/infra/ping"
        }) {
      client.get().uri(path).exchange().expectStatus().isNotFound();
    }
    client.post().uri("/v3/api-docs/users").exchange().expectStatus().is4xxClientError();
  }

  private JsonNode getJson(String path) {
    return client
        .get()
        .uri(path)
        .headers(
            headers -> {
              if (path.startsWith("/api/")) {
                headers.setBearerAuth(testUserToken());
              }
            })
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(JsonNode.class)
        .returnResult()
        .getResponseBody();
  }

  private String testUserToken() {
    try {
      long now = System.currentTimeMillis() / 1000;
      String header = encodeBase64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
      String payload =
          encodeBase64Url(
              "{\"iss\":\"logistics-users\",\"sub\":\"user01\","
                  + "\"user_id\":\"11111111-1111-1111-1111-111111111111\",\"role\":\"USER\","
                  + "\"is_delivery_staff\":false,\"iat\":"
                  + now
                  + ",\"exp\":"
                  + (now + 300)
                  + "}");
      String content = header + "." + payload;
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(
          new SecretKeySpec(
              "test-only-signing-key-for-gateway-module-tests-32bytes"
                  .getBytes(StandardCharsets.UTF_8),
              "HmacSHA256"));
      return content
          + "."
          + Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.GeneralSecurityException exception) {
      throw new IllegalStateException("Could not create a test JWT", exception);
    }
  }

  private String encodeBase64Url(String value) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private static Map<String, DisposableServer> startDownstreams() {
    Map<String, DisposableServer> servers = new LinkedHashMap<>();
    try {
      for (var entry : PUBLIC_PATHS.entrySet()) {
        String service = entry.getKey();
        String publicPath = entry.getValue();
        DisposableServer server =
            HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle(
                    (request, response) -> {
                      String body;
                      if (request.uri().equals("/v3/api-docs")) {
                        body =
                            """
                    {"openapi":"3.0.1","info":{"title":"%s","version":"test"},
                    "servers":[{"url":"/"}],"paths":{"%s":{"get":{"responses":{"200":{"description":"OK"}}}}}}
                    """
                                .formatted(service, publicPath);
                      } else if ("users".equals(service)
                          && "test-only-gateway-service-token"
                              .equals(request.requestHeaders().get("X-Service-Token"))
                          && !request.requestHeaders().contains("Authorization")
                          && request
                              .uri()
                              .equals(
                                  "/internal/users/11111111-1111-1111-1111-111111111111/authorization")) {
                        body =
                            "{\"data\":{\"user_id\":\"11111111-1111-1111-1111-111111111111\","
                                + "\"username\":\"user01\","
                                + "\"active\":true,\"role\":\"USER\","
                                + "\"is_delivery_staff\":false}}";
                      } else if (request.uri().equals(publicPath)) {
                        body = "{\"service\":\"" + service + "\"}";
                      } else {
                        return response.status(404).send();
                      }
                      return response
                          .header("Content-Type", "application/json")
                          .sendString(Mono.just(body));
                    })
                .bindNow();
        servers.put(service, server);
      }
      return servers;
    } catch (RuntimeException error) {
      servers.values().forEach(DisposableServer::disposeNow);
      throw error;
    }
  }
}
