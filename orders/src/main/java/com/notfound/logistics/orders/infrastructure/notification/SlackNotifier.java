package com.notfound.logistics.orders.infrastructure.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SlackNotifier {
  private static final Logger LOG = LoggerFactory.getLogger(SlackNotifier.class);
  private final boolean enabled;
  private final String url;
  private final ObjectMapper mapper;
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  public SlackNotifier(
      @Value("${slack.enabled:false}") boolean enabled,
      @Value("${slack.webhook-url:}") String url,
      ObjectMapper mapper) {
    this.enabled = enabled;
    this.url = url;
    this.mapper = mapper;
    if (enabled && url.isBlank())
      throw new IllegalStateException("Enabled Slack notifications require a webhook URL");
  }

  public void confirmed(UUID orderId, UUID deliveryId) {
    if (!enabled) return;
    try {
      var request =
          HttpRequest.newBuilder(URI.create(url))
              .timeout(Duration.ofSeconds(2))
              .header("Content-Type", "application/json")
              .POST(
                  HttpRequest.BodyPublishers.ofString(
                      mapper.writeValueAsString(
                          Map.of("text", "주문 확정: " + orderId + " / 배송: " + deliveryId))))
              .build();
      var response = client.send(request, HttpResponse.BodyHandlers.discarding());
      if (response.statusCode() < 200 || response.statusCode() >= 300)
        LOG.warn("Order notification failed; orderId={}", orderId);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      LOG.warn("Order notification interrupted; orderId={}", orderId);
    } catch (Exception exception) {
      // Do not log a webhook URL, token, or response body.
      LOG.warn("Order notification failed; orderId={}", orderId);
    }
  }

  @PreDestroy
  public void close() {
    client.close();
  }
}
