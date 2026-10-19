package com.notfound.logistics.orders.infrastructure.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notfound.logistics.common.api.ApiResponse;
import com.notfound.logistics.orders.domain.model.Order;
import com.notfound.logistics.orders.infrastructure.client.OrderClientTypes.Delivery;
import com.notfound.logistics.orders.infrastructure.client.UserIdentityClient;
import com.notfound.logistics.orders.infrastructure.client.UserIdentityClient.Identity;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.math.BigInteger;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SlackNotifierTest {
  @TempDir Path serverDirectory;
  private final UserIdentityClient users = mock(UserIdentityClient.class);
  private final Order order = order();
  private final Delivery delivery =
      new Delivery(order.getDeliveryId(), order.getId(), "HUB_WAITING", null);

  @Test
  void transportFailureDoesNotEscapeTheNotificationBoundary() throws Exception {
    var client = mock(HttpClient.class);
    when(client.send(any(), any())).thenThrow(new IOException("fixture transport failure"));
    try (var notifier = notifier(true, client)) {
      assertThatCode(() -> notifier.value.confirmed(order, delivery)).doesNotThrowAnyException();
      verify(client).send(any(), any());
    }
  }

  @Test
  void unsuccessfulWebhookResponseDoesNotEscapeTheNotificationBoundary() throws Exception {
    var client = mock(HttpClient.class);
    HttpResponse<?> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(500);
    doAnswer(invocation -> response).when(client).send(any(), any());
    try (var notifier = notifier(true, client)) {
      assertThatCode(() -> notifier.value.confirmed(order, delivery)).doesNotThrowAnyException();
      verify(client).send(any(), any());
    }
  }

  @Test
  void disabledNotificationsNeverUseTheTransport() {
    var client = mock(HttpClient.class);
    try (var notifier = notifier(false, client)) {
      notifier.value.confirmed(order, delivery);
      verifyNoInteractions(client, users);
    }
  }

  @Test
  void sendsReadableStructuredDetailsAndNamesThroughTheRealHttpClient() throws Exception {
    UUID assigneeId = UUID.randomUUID();
    when(users.identities("buyer01", assigneeId))
        .thenReturn(
            new ApiResponse<>(
                List.of(
                    new Identity(UUID.randomUUID(), "buyer01", "김주문"),
                    new Identity(assigneeId, "driver01", "이배송"))));
    var payload =
        send(
            new Delivery(delivery.deliveryId(), delivery.orderId(), "HUB_MOVING", assigneeId), 200);
    assertThat(payload.at("/blocks/0/text/text").asText()).isEqualTo("주문 확정");
    var fields = payload.at("/blocks/1/fields");
    assertThat(fields.size()).isEqualTo(8);
    for (var field : fields) assertThat(field.path("type").asText()).isEqualTo("plain_text");
    assertThat(fields.toString())
        .contains(
            "김주문 (buyer01)",
            "이배송 (driver01)",
            "상품",
            "감자",
            "12개",
            "10,000원",
            "120,000원",
            "허브 이동 중",
            "박수령");
    assertThat(payload.path("text").asText())
        .contains(
            "김주문 (buyer01)",
            "이배송 (driver01)",
            "서울시 배송로 1",
            order.getId().toString(),
            order.getDeliveryId().toString());
    verify(users).identities("buyer01", assigneeId);
  }

  @Test
  void unassignedDeliveryAndUnavailableNamesHaveDistinctFallbacks() throws Exception {
    when(users.identities("buyer01", null)).thenThrow(new IllegalStateException("unavailable"));
    assertThat(send(delivery, 200).path("text").asText()).contains("buyer01", "미배정", "감자");
    UUID assigneeId = UUID.randomUUID();
    when(users.identities("buyer01", assigneeId)).thenReturn(new ApiResponse<>(List.of()));
    assertThat(
            send(
                    new Delivery(
                        delivery.deliveryId(), delivery.orderId(), "HUB_WAITING", assigneeId),
                    500)
                .path("text")
                .asText())
        .contains("배정됨 · 이름 확인 불가", assigneeId.toString())
        .doesNotContain("미배정");
  }

  @Test
  void textCannotCreateMentionsAndLongNotesStayWithinSlackLimits() throws Exception {
    when(order.getProductName()).thenReturn("<!channel> <@U123> & 상품");
    when(order.getRequestNotes()).thenReturn("긴 요청".repeat(1000));
    var payload = send(delivery, 200);
    assertThat(payload.path("text").asText())
        .contains("&lt;!channel&gt;", "&lt;@U123&gt;", "&amp;")
        .doesNotContain("<!channel>");
    assertThat(payload.at("/blocks/1/fields/1/type").asText()).isEqualTo("plain_text");
    assertThat(payload.at("/blocks/3/text/text").asText()).hasSize(1005).endsWith("…");
  }

  private com.fasterxml.jackson.databind.JsonNode send(Delivery actual, int status)
      throws Exception {
    var received = new AtomicReference<String>();
    var server = new Tomcat();
    server.setBaseDir(serverDirectory.toString());
    server.setHostname("127.0.0.1");
    server.setPort(0);
    server.getConnector().setProperty("address", "127.0.0.1");
    var context = server.addContext("", serverDirectory.toString());
    Tomcat.addServlet(
        context,
        "webhook",
        new HttpServlet() {
          @Override
          protected void doPost(HttpServletRequest request, HttpServletResponse response)
              throws IOException {
            received.set(
                new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            response.setStatus(status);
          }
        });
    context.addServletMappingDecoded("/webhook", "webhook");
    server.start();
    try (var notifier =
        new Notifier(
            new SlackNotifier(
                true,
                "http://127.0.0.1:" + server.getConnector().getLocalPort() + "/webhook",
                new ObjectMapper(),
                users))) {
      notifier.value.confirmed(order, actual);
      assertThat(received.get()).isNotNull();
      return new ObjectMapper().readTree(received.get());
    } finally {
      server.stop();
      server.destroy();
    }
  }

  private static Order order() {
    var order = mock(Order.class);
    when(order.getId()).thenReturn(UUID.randomUUID());
    when(order.getDeliveryId()).thenReturn(UUID.randomUUID());
    when(order.getCreatedBy()).thenReturn("buyer01");
    when(order.getProductName()).thenReturn("감자");
    when(order.getQuantity()).thenReturn(12L);
    when(order.getUnitPrice()).thenReturn(10000L);
    when(order.totalPrice()).thenReturn(BigInteger.valueOf(120000));
    when(order.getDeliveryAddress()).thenReturn("서울시 배송로 1");
    when(order.getRecipientName()).thenReturn("박수령");
    return order;
  }

  private Notifier notifier(boolean enabled, HttpClient client) {
    var builder = mock(HttpClient.Builder.class, RETURNS_SELF);
    when(builder.build()).thenReturn(client);
    try (var factory = mockStatic(HttpClient.class)) {
      factory.when(HttpClient::newBuilder).thenReturn(builder);
      return new Notifier(
          new SlackNotifier(enabled, "https://test.invalid/webhook", new ObjectMapper(), users));
    }
  }

  private record Notifier(SlackNotifier value) implements AutoCloseable {
    @Override
    public void close() {
      value.close();
    }
  }
}
