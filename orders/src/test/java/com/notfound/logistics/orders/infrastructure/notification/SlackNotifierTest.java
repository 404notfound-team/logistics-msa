package com.notfound.logistics.orders.infrastructure.notification;

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
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SlackNotifierTest {
  @Test
  void transportFailureDoesNotEscapeTheNotificationBoundary() throws Exception {
    var client = mock(HttpClient.class);
    when(client.send(any(), any())).thenThrow(new IOException("fixture transport failure"));
    try (var notifier = notifier(true, client)) {
      assertThatCode(() -> notifier.value.confirmed(UUID.randomUUID(), UUID.randomUUID()))
          .doesNotThrowAnyException();
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
      assertThatCode(() -> notifier.value.confirmed(UUID.randomUUID(), UUID.randomUUID()))
          .doesNotThrowAnyException();
      verify(client).send(any(), any());
    }
  }

  @Test
  void disabledNotificationsNeverUseTheTransport() {
    var client = mock(HttpClient.class);
    try (var notifier = notifier(false, client)) {
      notifier.value.confirmed(UUID.randomUUID(), UUID.randomUUID());
      verifyNoInteractions(client);
    }
  }

  private Notifier notifier(boolean enabled, HttpClient client) {
    var builder = mock(HttpClient.Builder.class, RETURNS_SELF);
    when(builder.build()).thenReturn(client);
    try (var factory = mockStatic(HttpClient.class)) {
      factory.when(HttpClient::newBuilder).thenReturn(builder);
      return new Notifier(
          new SlackNotifier(enabled, "https://test.invalid/webhook", new ObjectMapper()));
    }
  }

  private record Notifier(SlackNotifier value) implements AutoCloseable {
    @Override
    public void close() {
      value.close();
    }
  }
}
