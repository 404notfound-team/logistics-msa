package com.notfound.logistics.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

final class GatewayClient implements AutoCloseable {
  static final ObjectMapper JSON = new ObjectMapper();
  private final URI base;
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  GatewayClient(URI base) {
    this.base = base;
  }

  String login(ComposeStack stack, String username, String role) throws Exception {
    final var password = stack.command("exec", "-T", "users", "printenv", "DEV_DATA_PASSWORD");
    assertThat(password).as("DEV_DATA_PASSWORD must be configured").isNotBlank();
    final var data =
        expect(
            request(
                "POST",
                "/api/users/signin",
                null,
                Map.of("username", username, "password", password),
                null,
                Duration.ofSeconds(10)),
            200);
    final var bearer = data.path("access_token").asText();
    final var claims = JSON.readTree(Base64.getUrlDecoder().decode(bearer.split("\\.")[1]));
    assertThat(claims.path("sub").asText()).isEqualTo(username);
    assertThat(claims.path("role").asText()).isEqualTo(role);
    return bearer;
  }

  Response request(String method, String path, String bearer, Object body, UUID key)
      throws Exception {
    return request(method, path, bearer, body, key, Duration.ofSeconds(30));
  }

  private Response request(
      String method, String path, String bearer, Object body, UUID key, Duration timeout)
      throws Exception {
    final var builder =
        HttpRequest.newBuilder(base.resolve(path))
            .timeout(timeout)
            .header("Accept", "application/json");
    if (bearer != null) {
      builder.header("Authorization", "Bearer " + bearer);
    }
    if (key != null) {
      builder.header("Idempotency-Key", key.toString());
    }
    final var publisher =
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body));
    if (body != null) {
      builder.header("Content-Type", "application/json");
    }
    final var response =
        client.send(
            builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
    return new Response(
        response.statusCode(),
        response.body().isBlank() ? NullNode.getInstance() : JSON.readTree(response.body()));
  }

  JsonNode get(String path, String bearer) throws Exception {
    return expect(request("GET", path, bearer, null, null), 200);
  }

  static JsonNode expect(Response response, int expected) {
    assertThat(response.status())
        .as("HTTP status, code=%s", response.body().path("code").asText())
        .isEqualTo(expected);
    return response.body().has("data") ? response.body().get("data") : response.body();
  }

  @Override
  public void close() {
    client.close();
  }

  record Response(int status, JsonNode body) {}
}
