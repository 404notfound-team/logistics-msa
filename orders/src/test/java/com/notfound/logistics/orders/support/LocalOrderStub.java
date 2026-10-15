package com.notfound.logistics.orders.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class LocalOrderStub implements AutoCloseable {
  public static final String SECRET = "orders-integration-fixture-secret-32-bytes-minimum";
  public static final String SERVICE_TOKEN = "orders-integration-service-token";
  public static final String RECEIVER = "147259cc-6ff6-42c7-a89a-8798c8187811";
  public static final String SUPPLIER = "1ef5bfb1-6b53-40ef-b09a-54eb71846d73";
  public static final String PRODUCT = "64f1ff8f-cd59-4504-b665-ef18727a6306";
  private final ObjectMapper mapper = new ObjectMapper();
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  private Process process;
  private Path directory;
  private String baseUrl;

  public synchronized String start() {
    if (process != null) return baseUrl;
    try {
      directory = Files.createTempDirectory("logistics-orders-stub-");
      var builder =
          new ProcessBuilder(
              "python3",
              System.getProperty("orders.stub.script"),
              "--port",
              "0",
              "--state",
              directory.resolve("state.sqlite").toString());
      builder.environment().put("JWT_SECRET", SECRET);
      builder.environment().put("ORDERS_SERVICE_TOKEN", SERVICE_TOKEN);
      builder.redirectError(ProcessBuilder.Redirect.INHERIT);
      process = builder.start();
      var reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
      String line =
          CompletableFuture.supplyAsync(
                  () -> {
                    try {
                      return reader.readLine();
                    } catch (Exception exception) {
                      throw new IllegalStateException(exception);
                    }
                  })
              .get(10, TimeUnit.SECONDS);
      if (line == null) throw new IllegalStateException("Stub exited before startup");
      baseUrl = "http://127.0.0.1:" + mapper.readTree(line).path("port").asInt();
      var observation =
          new ProcessBuilder(
                  "ps", "-p", Long.toString(process.pid()), "-o", "lstart=", "-o", "command=")
              .start();
      String identity = new String(observation.getInputStream().readAllBytes()).strip();
      observation.waitFor();
      System.out.println(
          "ORDER_STUB_RESOURCE "
              + mapper.writeValueAsString(
                  Map.of(
                      "pid", process.pid(), "identity", identity, "path", directory.toString())));
      return baseUrl;
    } catch (Exception exception) {
      close();
      throw new IllegalStateException("Cannot start Orders HTTP fixture", exception);
    }
  }

  public JsonNode call(String method, String path, Object body) {
    var response = exchange(method, path, body);
    try {
      if (response.statusCode() != 200)
        throw new IllegalStateException("Fixture returned " + response.statusCode());
      return mapper.readTree(response.body()).path("data");
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  public int status(String method, String path, Object body) {
    return exchange(method, path, body).statusCode();
  }

  private HttpResponse<String> exchange(String method, String path, Object body) {
    try {
      var request =
          HttpRequest.newBuilder(URI.create(start() + path))
              .timeout(Duration.ofSeconds(5))
              .header("X-Service-Token", SERVICE_TOKEN)
              .header("Content-Type", "application/json")
              .method(
                  method,
                  body == null
                      ? HttpRequest.BodyPublishers.noBody()
                      : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
              .build();
      return client.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  public void control(Map<String, Object> body) {
    call("POST", "/__stub/control", body);
  }

  public JsonNode state() {
    return call("GET", "/__stub/state", null);
  }

  public String token(String username, String role) {
    return call("POST", "/__stub/token", Map.of("username", username, "role", role))
        .path("access_token")
        .asText();
  }

  public void awaitIdle() {
    long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
    while (System.nanoTime() < deadline) {
      if (state().path("inflight").asInt() == 0) return;
      try {
        Thread.sleep(20);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(exception);
      }
    }
    throw new IllegalStateException("Fixture requests did not finish");
  }

  @Override
  public synchronized void close() {
    client.close();
    if (process != null) {
      process.destroy();
      try {
        if (!process.waitFor(12, TimeUnit.SECONDS)) {
          process.destroyForcibly();
          process.waitFor(3, TimeUnit.SECONDS);
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        process.destroyForcibly();
      }
      if (process.isAlive()) throw new IllegalStateException("Fixture process remains");
    }
    if (directory != null) {
      try (var paths = Files.walk(directory)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
      } catch (Exception exception) {
        throw new IllegalStateException("Fixture files remain", exception);
      }
      System.out.println("ORDER_STUB_CLEANED path=" + directory);
    }
  }
}
