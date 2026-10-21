package com.notfound.logistics.orders.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OrderRuntimeConfigurationTest {
  private final ApplicationContextRunner context =
      new ApplicationContextRunner().withUserConfiguration(OrderRuntimeConfiguration.class);

  @Test
  void missingServiceTokenStopsStartupBeforeAnyFeignRequest() {
    context.run(application -> assertThat(application).hasFailed());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "                ", "too-short"})
  void invalidServiceTokenStopsStartup(String token) {
    context
        .withPropertyValues("orders.clients.service-token=" + token)
        .run(
            application ->
                assertThat(application.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasStackTraceContaining(
                        "ORDERS_SERVICE_TOKEN requires at least 16 characters"));
  }

  @Test
  void aConfiguredServiceTokenAllowsStartup() {
    context
        .withPropertyValues("orders.clients.service-token=orders-test-token")
        .run(application -> assertThat(application).hasNotFailed());
  }
}
