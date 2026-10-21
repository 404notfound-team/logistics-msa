package com.notfound.logistics.hub.application.service.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notfound.logistics.hub.HubApplication;
import com.notfound.logistics.hub.support.HubSecurityTestTokens;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class StockRestorationRestartIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> DATABASE =
      new PostgreSQLContainer<>("postgres:18").withInitScript("db/hub-test-schema.sql");

  @Test
  void lostHttpAcknowledgementCanBeReplayedAfterHubRestartsWithoutAnotherIncrement()
      throws Exception {
    UUID order = UUID.randomUUID();
    UUID stock = UUID.randomUUID();
    UUID product = UUID.randomUUID();
    UUID hub = UUID.fromString("00000000-0000-0000-0000-000000000001");
    String body =
        new ObjectMapper()
            .writeValueAsString(
                Map.of("order_id", order, "hub_id", hub, "product_id", product, "quantity", 3));
    try (var first = application("create")) {
      var jdbc = first.getBean(JdbcTemplate.class);
      jdbc.update(
          "INSERT INTO hub.p_stocks(id,hub_id,product_id,quantity,created_at,created_by,updated_at,updated_by) VALUES(?,?,?,10,NOW(),'alice',NOW(),'alice')",
          stock,
          hub,
          product);
      try (var http = HttpClient.newHttpClient()) {
        var deduction =
            HttpRequest.newBuilder(
                    URI.create(
                        "http://127.0.0.1:"
                            + first.getWebServer().getPort()
                            + "/internal/stocks/deductions"))
                .header("Content-Type", "application/json")
                .header("X-Service-Token", HubSecurityTestTokens.ORDERS)
                .header("X-Actor-Username", "alice")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        assertThat(http.send(deduction, HttpResponse.BodyHandlers.ofString()).statusCode())
            .isEqualTo(200);
      }
      // Send the request and close the connection without reading its acknowledgement.
      try (var socket = new Socket("127.0.0.1", first.getWebServer().getPort())) {
        String request =
            "POST /internal/stocks/restorations HTTP/1.1\r\nHost: localhost\r\n"
                + "Content-Type: application/json\r\nX-Service-Token: "
                + HubSecurityTestTokens.ORDERS
                + "\r\nX-Actor-Username: cancel-admin\r\nContent-Length: "
                + body.getBytes(StandardCharsets.UTF_8).length
                + "\r\nConnection: close\r\n\r\n"
                + body;
        socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
        socket.shutdownOutput();
        await()
            .atMost(Duration.ofSeconds(5))
            .untilAsserted(
                () ->
                    assertThat(
                            jdbc.queryForObject(
                                "SELECT count(*) FROM hub.p_stock_restorations WHERE order_id=?",
                                Long.class,
                                order))
                        .isEqualTo(1));
      }
      assertThat(
              jdbc.queryForObject(
                  "SELECT quantity FROM hub.p_stocks WHERE id=?", Long.class, stock))
          .isEqualTo(10);
    }
    try (var restarted = application("validate");
        var http = HttpClient.newHttpClient()) {
      var request =
          HttpRequest.newBuilder(
                  URI.create(
                      "http://127.0.0.1:"
                          + restarted.getWebServer().getPort()
                          + "/internal/stocks/restorations"))
              .header("Content-Type", "application/json")
              .header("X-Service-Token", HubSecurityTestTokens.ORDERS)
              .header("X-Actor-Username", "cancel-admin")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
      var response = http.send(request, HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
      var data = new ObjectMapper().readTree(response.body()).path("data");
      assertThat(data.path("quantity").asLong()).isEqualTo(10);
      assertThat(data.path("id").asText()).isEqualTo(stock.toString());
      var jdbc = restarted.getBean(JdbcTemplate.class);
      assertThat(
              jdbc.queryForObject(
                  "SELECT quantity FROM hub.p_stocks WHERE id=?", Long.class, stock))
          .isEqualTo(10);
      assertThat(jdbc.queryForObject("SELECT count(*) FROM hub.p_stock_restorations", Long.class))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT created_by FROM hub.p_stock_restorations WHERE order_id=?",
                  String.class,
                  order))
          .isEqualTo("cancel-admin");
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void lostDeductionSuccessOrRejectionSurvivesHubRestart(boolean rejected) throws Exception {
    UUID order = UUID.randomUUID();
    UUID stock = UUID.randomUUID();
    UUID product = UUID.randomUUID();
    UUID hub = UUID.fromString("00000000-0000-0000-0000-000000000001");
    String body =
        new ObjectMapper()
            .writeValueAsString(
                Map.of("order_id", order, "hub_id", hub, "product_id", product, "quantity", 3));
    try (var first = application("create")) {
      var jdbc = first.getBean(JdbcTemplate.class);
      jdbc.update(
          "INSERT INTO hub.p_stocks(id,hub_id,product_id,quantity,created_at,created_by,updated_at,updated_by) VALUES(?,?,?,?,NOW(),'alice',NOW(),'alice')",
          stock,
          hub,
          product,
          rejected ? 2 : 10);
      try (var socket = new Socket("127.0.0.1", first.getWebServer().getPort())) {
        String request =
            "POST /internal/stocks/deductions HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\nX-Service-Token: "
                + HubSecurityTestTokens.ORDERS
                + "\r\nX-Actor-Username: alice\r\nContent-Length: "
                + body.getBytes(StandardCharsets.UTF_8).length
                + "\r\nConnection: close\r\n\r\n"
                + body;
        socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
        socket.shutdownOutput();
        await()
            .atMost(Duration.ofSeconds(5))
            .untilAsserted(
                () ->
                    assertThat(
                            jdbc.queryForObject(
                                "SELECT count(*) FROM hub.p_stock_deductions WHERE order_id=?",
                                Long.class,
                                order))
                        .isEqualTo(1));
      }
    }
    try (var restarted = application("validate");
        var http = HttpClient.newHttpClient()) {
      var request =
          HttpRequest.newBuilder(
                  URI.create(
                      "http://127.0.0.1:"
                          + restarted.getWebServer().getPort()
                          + "/internal/stocks/deductions"))
              .header("Content-Type", "application/json")
              .header("X-Service-Token", HubSecurityTestTokens.ORDERS)
              .header("X-Actor-Username", "admin")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
      var response = http.send(request, HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).as(response.body()).isEqualTo(rejected ? 409 : 200);
      var jdbc = restarted.getBean(JdbcTemplate.class);
      assertThat(
              jdbc.queryForObject(
                  "SELECT quantity FROM hub.p_stocks WHERE id=?", Long.class, stock))
          .isEqualTo(rejected ? 2L : 7L);
      assertThat(jdbc.queryForObject("SELECT count(*) FROM hub.p_stock_deductions", Long.class))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT created_by FROM hub.p_stock_deductions WHERE order_id=?",
                  String.class,
                  order))
          .isEqualTo("alice");
      if (rejected)
        assertThat(new ObjectMapper().readTree(response.body()).path("code").asText())
            .isEqualTo("INSUFFICIENT_STOCK");
      else
        assertThat(new ObjectMapper().readTree(response.body()).at("/data/quantity").asLong())
            .isEqualTo(7);
    }
  }

  private ServletWebServerApplicationContext application(String ddl) {
    var arguments = new ArrayList<String>();
    Map.ofEntries(
            Map.entry("server.port", "0"),
            Map.entry("eureka.client.enabled", "false"),
            Map.entry("management.tracing.enabled", "false"),
            Map.entry("spring.datasource.url", DATABASE.getJdbcUrl()),
            Map.entry("spring.datasource.username", DATABASE.getUsername()),
            Map.entry("spring.datasource.password", DATABASE.getPassword()),
            Map.entry("spring.jpa.hibernate.ddl-auto", ddl),
            Map.entry("spring.jpa.defer-datasource-initialization", "true"),
            Map.entry("spring.sql.init.mode", "always"),
            Map.entry(
                "spring.sql.init.data-locations",
                "classpath:db/seed/data.sql,classpath:db/seed/hub-routes.sql"),
            Map.entry("hub.jwt.secret", HubSecurityTestTokens.SECRET),
            Map.entry("hub.internal-auth.service-token", "hub-service-test-token"),
            Map.entry("hub.internal-auth.clients.orders.token", HubSecurityTestTokens.ORDERS))
        .forEach((name, value) -> arguments.add("--" + name + "=" + value));
    return (ServletWebServerApplicationContext)
        new SpringApplicationBuilder(HubApplication.class).run(arguments.toArray(String[]::new));
  }
}
