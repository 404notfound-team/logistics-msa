package com.notfound.logistics.orders.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.notfound.logistics.orders.infrastructure.audit.OrdersAuditorAware;
import com.notfound.logistics.orders.infrastructure.client.OrderClientTypes.Deduction;
import feign.Client;
import feign.Request;
import feign.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.context.annotation.Configuration;

class FeignTimeoutConfigurationTest {
  @Test
  void businessClientsUseYamlDefaultsAndKeepTheInfrastructureProbeIndependent() {
    verifyTimeouts(1000, 2000);
  }

  @Test
  void businessTimeoutOverridesReachAllBusinessFeignClients() {
    verifyTimeouts(
        125, 350, "orders.clients.connect-timeout-ms=125", "orders.clients.read-timeout-ms=350");
  }

  private void verifyTimeouts(int connect, int read, String... properties) {
    final var requests = new ArrayList<Request.Options>();
    final Client transport =
        (request, options) -> {
          requests.add(options);
          return Response.builder()
              .request(request)
              .status(200)
              .headers(Map.of("Content-Type", List.of("application/json")))
              .body(
                  request.url().endsWith("/internal/infra/ping")
                      ? "{\"status\":\"ok\"}"
                      : request.url().contains("/internal/users/identities")
                          ? "{\"data\":[]}"
                          : "{\"data\":{}}",
                  java.nio.charset.StandardCharsets.UTF_8)
              .build();
        };
    new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withConfiguration(
            AutoConfigurations.of(
                FeignAutoConfiguration.class,
                JacksonAutoConfiguration.class,
                HttpMessageConvertersAutoConfiguration.class))
        .withUserConfiguration(Clients.class)
        .withBean(Client.class, () -> transport)
        .withBean(OrdersAuditorAware.class)
        .withPropertyValues(
            "spring.profiles.active=local",
            "orders.clients.company-url=http://company.test",
            "orders.clients.stock-url=http://hub.test",
            "orders.clients.delivery-url=http://delivery.test",
            "orders.clients.users-url=http://users.test",
            "spring.cloud.openfeign.client.config.infraHubProbe.url=http://hub.test")
        .withPropertyValues(properties)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              final var id = UUID.randomUUID();
              context
                  .getBean(OrdersAuditorAware.class)
                  .runAsPersistedUser(
                      "original-user",
                      () -> {
                        context.getBean(CompanyClient.class).company(id);
                        context.getBean(StockClient.class).deduct(new Deduction(id, id, id, 3));
                        context.getBean(DeliveryClient.class).byOrder(id);
                        context.getBean(UserIdentityClient.class).identities("original-user", id);
                      });
              assertThat(requests)
                  .hasSize(4)
                  .allSatisfy(
                      options -> {
                        assertThat(options.connectTimeoutMillis()).isEqualTo(connect);
                        assertThat(options.readTimeoutMillis()).isEqualTo(read);
                        assertThat(options.isFollowRedirects()).isFalse();
                      });
              context.getBean(HubProbeClient.class).ping();
              final var probe = requests.getLast();
              assertThat(probe.connectTimeoutMillis()).isEqualTo(2000);
              assertThat(probe.readTimeoutMillis()).isEqualTo(5000);
              assertThat(probe.isFollowRedirects()).isTrue();
            });
  }

  @Configuration(proxyBeanMethods = false)
  @EnableFeignClients(
      clients = {
        CompanyClient.class,
        StockClient.class,
        DeliveryClient.class,
        UserIdentityClient.class,
        HubProbeClient.class
      })
  static class Clients {}
}
