package com.notfound.logistics.delivery.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;

import feign.Client;
import feign.Response;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.context.annotation.Configuration;

class UserAuthorizationClientTest {
  @Test
  void sendsOnlyServiceCredentialsAndDecodesTheUsersContract() {
    UUID id = UUID.randomUUID();
    Client transport =
        (request, options) -> {
          assertThat(request.url())
              .isEqualTo("http://users.test/internal/users/" + id + "/authorization");
          assertThat(request.headers().get("X-Service-Token"))
              .containsExactly("delivery-test-token");
          assertThat(request.headers()).doesNotContainKey("Authorization");
          return Response.builder()
              .request(request)
              .status(200)
              .headers(Map.of("Content-Type", List.of("application/json")))
              .body(
                  "{\"data\":{\"user_id\":\""
                      + id
                      + "\",\"username\":\"driver01\",\"active\":true,\"role\":\"USER\",\"is_delivery_staff\":true}}",
                  StandardCharsets.UTF_8)
              .build();
        };
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                FeignAutoConfiguration.class,
                JacksonAutoConfiguration.class,
                HttpMessageConvertersAutoConfiguration.class))
        .withUserConfiguration(Clients.class)
        .withBean(Client.class, () -> transport)
        .withPropertyValues(
            "delivery.clients.users-url=http://users.test",
            "delivery.internal-auth.service-token=delivery-test-token",
            "spring.jackson.property-naming-strategy=SNAKE_CASE")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(UserAuthorizationClient.class).getAuthorization(id).data())
                  .isEqualTo(new UserAuthorizationClient.Authorization(id, true, true));
            });
  }

  @Configuration(proxyBeanMethods = false)
  @EnableFeignClients(clients = UserAuthorizationClient.class)
  static class Clients {}
}
