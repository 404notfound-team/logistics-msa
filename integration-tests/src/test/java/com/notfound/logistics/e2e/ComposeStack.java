package com.notfound.logistics.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Operates an already started stack; never creates containers or volumes. */
final class ComposeStack {
  private final Path root;
  private final String project;
  private final URI gateway;

  ComposeStack(Path root, String project, URI gateway) {
    validateProject(project);
    this.root = root;
    this.project = project;
    this.gateway = gateway;
  }

  static ComposeStack fromEnvironment() throws Exception {
    final var stack =
        new ComposeStack(
            Path.of(System.getProperty("repository.root")),
            System.getenv().getOrDefault("COMPOSE_PROJECT_NAME", ""),
            URI.create(System.getenv().getOrDefault("GATEWAY_URL", "http://127.0.0.1:8080")));
    validateGateway(stack.gateway, stack.port("gateway", 8080));
    return stack;
  }

  static void validateProject(String project) {
    if (!project.matches("logistics-orders-recovery-[a-z0-9][a-z0-9_-]*")) {
      throw new IllegalArgumentException(
          "Use a dedicated COMPOSE_PROJECT_NAME=logistics-orders-recovery-<name> stack.");
    }
  }

  static void validateGateway(URI gateway, int port) {
    if (!"http".equals(gateway.getScheme())
        || !("127.0.0.1".equals(gateway.getHost()) || "localhost".equals(gateway.getHost()))
        || (gateway.getPort() == -1 ? 80 : gateway.getPort()) != port
        || gateway.getRawUserInfo() != null
        || !gateway.getRawPath().isEmpty()
        || gateway.getRawQuery() != null
        || gateway.getRawFragment() != null) {
      throw new IllegalArgumentException("Gateway must match this Compose project's local port.");
    }
  }

  URI gateway() {
    return gateway;
  }

  int port(String service, int internal) throws Exception {
    final var mapped = command("port", service, Integer.toString(internal));
    return Integer.parseInt(mapped.substring(mapped.lastIndexOf(':') + 1).strip());
  }

  String command(String... args) throws Exception {
    final var command = new ArrayList<>(List.of("docker", "compose", "--project-name", project));
    command.addAll(List.of(args));
    final var process =
        new ProcessBuilder(command)
            .directory(root.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    try (var reader = Executors.newSingleThreadExecutor()) {
      final var output = reader.submit(() -> process.getInputStream().readAllBytes());
      try {
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
          throw new IOException("Compose command timed out: " + args[0]);
        }
        if (process.exitValue() != 0) {
          // Never include output: commands may read credentials into memory.
          throw new IOException("Compose command failed: " + args[0]);
        }
        return new String(output.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8).strip();
      } finally {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
        output.cancel(true);
      }
    }
  }

  void startOrders() throws Exception {
    command("start", "orders");
    await("Orders health after restart")
        .atMost(Duration.ofSeconds(90))
        .pollInterval(Duration.ofMillis(200))
        .until(
            () -> {
              try {
                return !command(
                        "exec",
                        "-T",
                        "orders",
                        "curl",
                        "--fail",
                        "--silent",
                        "--max-time",
                        "2",
                        "http://127.0.0.1:8080/actuator/health")
                    .isEmpty();
              } catch (IOException unavailable) {
                return false;
              }
            });
  }

  void requireDeliveryApis() throws Exception {
    final var missing = java.util.UUID.randomUUID().toString();
    final String[][] probes = {
      {"GET", "/internal/deliveries/by-order/" + missing, "404"},
      {"POST", "/internal/deliveries", "400"},
      {"POST", "/internal/deliveries/" + missing + "/cancel", "404"},
      {"DELETE", "/internal/deliveries/" + missing, "404"}
    };
    final var script =
        """
        exec curl --silent --show-error --max-time 10 --request "$1" \
          --header "X-Service-Token: $ORDERS_SERVICE_TOKEN" \
          --header "X-Actor-Username: devmaster" --header 'Content-Type: application/json' \
          --data '{}' --write-out '\\n%{http_code}' "http://127.0.0.1:8080$2"
        """;
    for (final var probe : probes) {
      final var output =
          command("exec", "-T", "delivery", "sh", "-eu", "-c", script, "sh", probe[0], probe[1]);
      verifyDeliveryProbe(probe[0] + " " + probe[1], output, Integer.parseInt(probe[2]));
    }
  }

  static void verifyDeliveryProbe(String path, String output, int expected) throws Exception {
    final int boundary = output.lastIndexOf('\n');
    assertThat(boundary).as("Delivery readiness: %s", path).isNotNegative();
    final int status = Integer.parseInt(output.substring(boundary + 1));
    final var body = GatewayClient.JSON.readTree(output.substring(0, boundary));
    assertThat(status)
        .as("Delivery readiness: %s, code=%s", path, body.path("code").asText())
        .isEqualTo(expected);
    if (expected == 404) {
      assertThat(body.path("code").asText()).isEqualTo("DELIVERY_NOT_FOUND");
    }
  }

  static final class OrdersRestart implements AutoCloseable {
    private final ComposeStack stack;
    private boolean stopped;

    OrdersRestart(ComposeStack stack) {
      this.stack = stack;
    }

    void stop() throws Exception {
      stopped = true;
      stack.command("stop", "-t", "10", "orders");
    }

    void start() throws Exception {
      stack.startOrders();
      stopped = false;
    }

    @Override
    public void close() throws Exception {
      if (stopped) {
        start();
      }
    }
  }
}
