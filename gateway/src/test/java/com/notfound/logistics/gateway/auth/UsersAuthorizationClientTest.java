package com.notfound.logistics.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.notfound.logistics.gateway.auth.UsersAuthorizationClient.UsersUnavailableException;
import java.net.ConnectException;
import java.net.URI;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;

class UsersAuthorizationClientTest {
  private static final String SERVICE_TOKEN = "test-only-gateway-service-token";
  private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

  private final AtomicReference<ClientRequest> sent = new AtomicReference<>();

  @Test
  void consultsUsersWithServiceTokenInsteadOfUserJwt() {
    var user =
        client(
                respond(
                    HttpStatus.OK,
                    """
                    {"data":{"user_id":"%s","username":"user01","active":true,"role":"USER",
                    "is_delivery_staff":false,"token_version":3}}
                    """
                        .formatted(USER_ID)))
            .fetch(USER_ID)
            .block();

    assertThat(user.username()).isEqualTo("user01");
    assertThat(user.isDeliveryStaff()).isFalse();
    assertThat(user.tokenVersion()).isEqualTo(3L);
    assertThat(sent.get().url().getPath())
        .isEqualTo("/internal/users/" + USER_ID + "/authorization");
    assertThat(sent.get().headers().getFirst("X-Service-Token")).isEqualTo(SERVICE_TOKEN);
    assertThat(sent.get().headers().containsKey(HttpHeaders.AUTHORIZATION)).isFalse();
  }

  @Test
  void reportsUnavailableWhenUsersFailsOrRejectsGatewayServiceToken() {
    for (HttpStatus status :
        new HttpStatus[] {HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.UNAUTHORIZED}) {
      assertThatThrownBy(() -> client(respond(status, "")).fetch(USER_ID).block())
          .isInstanceOf(UsersUnavailableException.class);
    }
  }

  @Test
  void reportsUnavailableWhenUsersIsUnreachableOrAnswersWithoutData() {
    ExchangeFunction unreachable =
        request ->
            Mono.error(
                new WebClientRequestException(
                    new ConnectException("refused"),
                    HttpMethod.GET,
                    URI.create("http://users"),
                    new HttpHeaders()));

    assertThatThrownBy(() -> client(unreachable).fetch(USER_ID).block())
        .isInstanceOf(UsersUnavailableException.class);
    assertThatThrownBy(() -> client(respond(HttpStatus.OK, "{}")).fetch(USER_ID).block())
        .isInstanceOf(UsersUnavailableException.class);
  }

  @Test
  void refusesToStartWithoutServiceToken() {
    assertThatThrownBy(() -> new UsersAuthorizationClient(WebClient.builder(), " "))
        .isInstanceOf(IllegalStateException.class);
  }

  private UsersAuthorizationClient client(ExchangeFunction users) {
    return new UsersAuthorizationClient(WebClient.builder().exchangeFunction(users), SERVICE_TOKEN);
  }

  private ExchangeFunction respond(HttpStatus status, String body) {
    return request -> {
      sent.set(request);
      return Mono.just(
          ClientResponse.create(status)
              .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
              .body(body)
              .build());
    };
  }
}
