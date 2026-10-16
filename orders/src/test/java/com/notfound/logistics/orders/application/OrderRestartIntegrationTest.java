package com.notfound.logistics.orders.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notfound.logistics.orders.OrdersApplication;
import com.notfound.logistics.orders.domain.model.OrderStatus;
import com.notfound.logistics.orders.domain.repository.OrderRepository;
import com.notfound.logistics.orders.support.LocalOrderStub;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class OrderRestartIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> DATABASE =
      new PostgreSQLContainer<>("postgres:18-alpine")
          .withLabel("com.notfound.logistics.task", "orders-complete-01a13e9f");

  @Test
  void aNewApplicationInstanceAutomaticallyRecoversPersistedInputAndIgnoresLiveProcessing()
      throws Exception {
    try (var stub = new LocalOrderStub();
        var http = HttpClient.newHttpClient()) {
      String stubUrl = stub.start();
      String jwt = stub.token("alice", "USER");
      UUID key = UUID.randomUUID();
      String body =
          new ObjectMapper()
              .writeValueAsString(
                  Map.of(
                      "receiver_company_id",
                      LocalOrderStub.RECEIVER,
                      "supplier_company_id",
                      LocalOrderStub.SUPPLIER,
                      "product_id",
                      LocalOrderStub.PRODUCT,
                      "quantity",
                      3,
                      "delivery_address",
                      "서울",
                      "recipient_name",
                      "수령인"));
      UUID id;
      try (var first = application(stubUrl, "create", false)) {
        int port = first.getWebServer().getPort();
        stub.control(
            Map.of(
                "action",
                "fault",
                "operation",
                "delivery.create",
                "remaining",
                1,
                "delay_before_ms",
                1800));
        var request =
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/orders"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + jwt)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", key.toString())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        id =
            UUID.fromString(
                new ObjectMapper().readTree(response.body()).at("/data/order_id").asText());
        assertThat(first.getBean(OrderRepository.class).findById(id).orElseThrow().getStatus())
            .isEqualTo(OrderStatus.RECOVERY_REQUIRED);
      }
      stub.awaitIdle();
      stub.control(
          Map.of(
              "action",
              "company_hub",
              "company_id",
              LocalOrderStub.SUPPLIER,
              "hub_id",
              "00000000-0000-0000-0000-000000000099"));
      stub.control(Map.of("action", "product", "name", "바뀐 상품", "price", "30000.00"));
      try (var restarted = application(stubUrl, "validate", true)) {
        var repository = restarted.getBean(OrderRepository.class);
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline
            && repository.findById(id).orElseThrow().getStatus() != OrderStatus.CONFIRMED)
          Thread.sleep(25);
        var order = repository.findById(id).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getProductName()).isEqualTo("테스트 상품");
        assertThat(order.getUnitPrice()).isEqualByComparingTo("10000.00");
        assertThat(order.getDepartureHubId().toString()).endsWith("000000000002");
        assertThat(order.getCreatedBy()).isEqualTo("alice");
        assertThat(order.getUpdatedBy()).isEqualTo("alice");
        assertThat(stub.state().at("/stocks/0/quantity").asLong()).isEqualTo(997);
        assertThat(stub.state().path("deliveries")).hasSize(1);
        assertThat(stub.state().path("notifications")).hasSize(1);
        // While a request owns a CONFIRMED -> CANCELLING transition, automatic recovery must skip
        // it.
        stub.control(
            Map.of(
                "action",
                "fault",
                "operation",
                "delivery.cancel",
                "remaining",
                1,
                "delay_after_ms",
                150));
        var cancel =
            HttpRequest.newBuilder(
                    URI.create(
                        "http://127.0.0.1:"
                            + restarted.getWebServer().getPort()
                            + "/api/orders/"
                            + id
                            + "/cancel"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + jwt)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        var cancelled =
            CompletableFuture.supplyAsync(
                    () -> {
                      try {
                        return http.send(cancel, HttpResponse.BodyHandlers.ofString());
                      } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                      }
                    })
                .get(10, TimeUnit.SECONDS);
        assertThat(cancelled.statusCode()).as(cancelled.body()).isEqualTo(200);
        assertThat(stub.state().at("/counts/delivery.cancel").asInt()).isEqualTo(1);
        assertThat(stub.state().at("/counts/stock.deduct").asInt()).isEqualTo(1);
      }
    }
  }

  private ServletWebServerApplicationContext application(String url, String ddl, boolean recover) {
    var arguments = new ArrayList<String>();
    Map.ofEntries(
            Map.entry("server.port", "0"),
            Map.entry("eureka.client.enabled", "false"),
            Map.entry("management.tracing.enabled", "false"),
            Map.entry("spring.datasource.url", DATABASE.getJdbcUrl()),
            Map.entry("spring.datasource.username", DATABASE.getUsername()),
            Map.entry("spring.datasource.password", DATABASE.getPassword()),
            Map.entry("spring.jpa.hibernate.ddl-auto", ddl),
            Map.entry("spring.jpa.properties.hibernate.default_schema", "public"),
            Map.entry("orders.jwt.secret", LocalOrderStub.SECRET),
            Map.entry("orders.clients.company-url", url),
            Map.entry("orders.clients.stock-url", url),
            Map.entry("orders.clients.delivery-url", url),
            Map.entry("orders.clients.service-token", LocalOrderStub.SERVICE_TOKEN),
            Map.entry("orders.clients.read-timeout-ms", "300"),
            Map.entry("orders.recovery.enabled", Boolean.toString(recover)),
            Map.entry("orders.recovery.delay-ms", "50"),
            Map.entry("slack.enabled", "true"),
            Map.entry("slack.webhook-url", url + "/__stub/slack"))
        .forEach((name, value) -> arguments.add("--" + name + "=" + value));
    return (ServletWebServerApplicationContext)
        new SpringApplicationBuilder(OrdersApplication.class).run(arguments.toArray(String[]::new));
  }
}
