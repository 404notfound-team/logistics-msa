package com.notfound.logistics.orders.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.notfound.logistics.orders.OrdersApplication;
import com.notfound.logistics.orders.domain.repository.OrderRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class OrderPriceSchemaIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:18-alpine");

  @Test
  void localStartupCreatesAndAutomaticallyConvertsPricesAndCanBeRepeated() {
    var jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(
                DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()));
    jdbc.execute("CREATE SCHEMA orders");
    try (var fresh = application()) {
      assertThat(fresh.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto"))
          .isEqualTo("update");
      assertBigint(jdbc);
    }

    // Reuse the actual application schema, changing only the legacy price column.
    jdbc.execute("ALTER TABLE orders.p_orders ALTER COLUMN unit_price TYPE numeric(15,2)");
    var ids = jdbc.queryForList("SELECT id FROM orders.p_orders ORDER BY id LIMIT 5", UUID.class);
    assertThat(ids).hasSize(5);
    var oldPrices = List.of("10000.00", "1.49", "1.50", "9999999999999.99", "0.00");
    var integerPrices = List.of(10000L, 1L, 2L, 10000000000000L, 0L);
    for (int i = 0; i < ids.size(); i++) {
      jdbc.update(
          "UPDATE orders.p_orders SET unit_price=? WHERE id=?",
          new BigDecimal(oldPrices.get(i)),
          ids.get(i));
    }
    var originalOrders =
        jdbc.queryForList(
            "SELECT id, status, delivery_id, created_by, deleted_at FROM orders.p_orders ORDER BY id");

    for (int restart = 0; restart < 2; restart++) {
      try (var restarted = application()) {
        assertBigint(jdbc);
        var repository = restarted.getBean(OrderRepository.class);
        for (int i = 0; i < ids.size(); i++) {
          assertThat(repository.findById(ids.get(i)).orElseThrow().getUnitPrice())
              .isEqualTo(integerPrices.get(i));
        }
        assertThat(
                jdbc.queryForList(
                    "SELECT id, status, delivery_id, created_by, deleted_at FROM orders.p_orders ORDER BY id"))
            .isEqualTo(originalOrders);
      }
    }
  }

  private void assertBigint(JdbcTemplate jdbc) {
    assertThat(
            jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns WHERE table_schema='orders' AND table_name='p_orders' AND column_name='unit_price'",
                String.class))
        .isEqualTo("bigint");
  }

  private ConfigurableApplicationContext application() {
    String url = DATABASE.getJdbcUrl();
    url += (url.contains("?") ? "&" : "?") + "currentSchema=orders";
    return new SpringApplicationBuilder(OrdersApplication.class)
        .profiles("local")
        .run(
            "--server.port=0",
            "--server.address=127.0.0.1",
            "--spring.datasource.url=" + url,
            "--spring.datasource.username=" + DATABASE.getUsername(),
            "--spring.datasource.password=" + DATABASE.getPassword(),
            "--eureka.client.enabled=false",
            "--management.tracing.enabled=false",
            "--orders.clients.service-token=price-upgrade-test-only-service-token",
            "--orders.recovery.enabled=false",
            "--slack.enabled=false");
  }
}
