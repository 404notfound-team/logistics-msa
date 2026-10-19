package com.notfound.logistics.orders.infrastructure.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notfound.logistics.orders.domain.model.Order;
import com.notfound.logistics.orders.infrastructure.client.OrderClientTypes.Delivery;
import com.notfound.logistics.orders.infrastructure.client.UserIdentityClient;
import com.notfound.logistics.orders.infrastructure.client.UserIdentityClient.Identity;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.NumberFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
  private final UserIdentityClient users;
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  public SlackNotifier(
      @Value("${slack.enabled:false}") boolean enabled,
      @Value("${slack.webhook-url:}") String url,
      ObjectMapper mapper,
      UserIdentityClient users) {
    this.enabled = enabled;
    this.url = url;
    this.mapper = mapper;
    this.users = users;
    if (enabled && url.isBlank())
      throw new IllegalStateException("Enabled Slack notifications require a webhook URL");
  }

  public void confirmed(Order order, Delivery delivery) {
    if (!enabled) return;
    try {
      var request =
          HttpRequest.newBuilder(URI.create(url))
              .timeout(Duration.ofSeconds(2))
              .header("Content-Type", "application/json")
              .POST(
                  HttpRequest.BodyPublishers.ofString(
                      mapper.writeValueAsString(message(order, delivery))))
              .build();
      var response = client.send(request, HttpResponse.BodyHandlers.discarding());
      if (response.statusCode() < 200 || response.statusCode() >= 300)
        LOG.warn("Order notification failed; orderId={}", order.getId());
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      LOG.warn("Order notification interrupted; orderId={}", order.getId());
    } catch (Exception exception) {
      // Do not log names, addresses, a webhook URL, token, or response body.
      LOG.warn("Order notification failed; orderId={}", order.getId());
    }
  }

  private Map<String, Object> message(Order order, Delivery delivery) {
    List<Identity> identities = identities(order, delivery);
    String orderer =
        identities.stream()
            .filter(identity -> order.getCreatedBy().equals(identity.username()))
            .map(identity -> displayName(identity, order.getCreatedBy()))
            .findFirst()
            .orElse(order.getCreatedBy());
    String assignee =
        delivery.assigneeId() == null
            ? "미배정"
            : identities.stream()
                .filter(identity -> delivery.assigneeId().equals(identity.id()))
                .map(identity -> displayName(identity, "배정됨 · 이름 확인 불가"))
                .findFirst()
                .orElse("배정됨 · 이름 확인 불가");
    var numbers = NumberFormat.getIntegerInstance(Locale.KOREA);
    var fields =
        List.of(
            field("주문자", orderer),
            field("상품", order.getProductName()),
            field("수량", numbers.format(order.getQuantity()) + "개"),
            field("단가", numbers.format(order.getUnitPrice()) + "원"),
            field("주문 금액", numbers.format(order.totalPrice()) + "원"),
            field("배송 상태", deliveryStatus(delivery.status())),
            field("배송 담당자", assignee),
            field("수령인", order.getRecipientName()));
    var blocks = new ArrayList<Map<String, Object>>();
    blocks.add(Map.of("type", "header", "text", plain("주문 확정")));
    blocks.add(Map.of("type", "section", "fields", fields));
    String destination = "배송지\n" + order.getDeliveryAddress();
    blocks.add(Map.of("type", "section", "text", plain(destination)));
    String notes = order.getRequestNotes();
    if (notes != null && !notes.isBlank()) {
      blocks.add(Map.of("type", "section", "text", plain("요청사항\n" + truncate(notes, 1000))));
    }
    String references = "주문 번호: " + order.getId() + "\n배송 번호: " + order.getDeliveryId();
    if (delivery.assigneeId() != null) references += "\n담당자 번호: " + delivery.assigneeId();
    blocks.add(Map.of("type", "context", "elements", List.of(plain(references))));
    String summary =
        "주문 확정\n"
            + fields.stream()
                .map(field -> field.get("text"))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("")
            + "\n"
            + destination
            + "\n"
            + references;
    // Top-level text is also used by notifications and screen readers. Escape Slack control syntax.
    return Map.of(
        "text",
        summary.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"),
        "blocks",
        blocks);
  }

  private List<Identity> identities(Order order, Delivery delivery) {
    try {
      var response = users.identities(order.getCreatedBy(), delivery.assigneeId());
      if (response != null && response.data() != null)
        return response.data().stream().filter(java.util.Objects::nonNull).toList();
    } catch (RuntimeException exception) {
      LOG.warn("Order notification names unavailable; orderId={}", order.getId());
    }
    return List.of();
  }

  private static String displayName(Identity identity, String fallback) {
    if (identity.name() == null || identity.name().isBlank()) return fallback;
    return identity.name() + " (" + identity.username() + ")";
  }

  private static Map<String, String> field(String label, String value) {
    return plain(label + "\n" + truncate(value, 1000));
  }

  private static Map<String, String> plain(String text) {
    return Map.of("type", "plain_text", "text", text);
  }

  private static String truncate(String value, int maximum) {
    return value.length() <= maximum ? value : value.substring(0, maximum - 1) + "…";
  }

  private static String deliveryStatus(String status) {
    return switch (status) {
      case "HUB_WAITING" -> "허브 대기 중";
      case "HUB_MOVING" -> "허브 이동 중";
      case "DESTINATION_HUB_ARRIVED" -> "목적지 허브 도착";
      case "COMPANY_MOVING" -> "업체 이동 중";
      case "DELIVERED" -> "배송 완료";
      case "CANCELLED" -> "배송 취소";
      default -> "상태 확인 필요";
    };
  }

  @PreDestroy
  public void close() {
    client.close();
  }
}
