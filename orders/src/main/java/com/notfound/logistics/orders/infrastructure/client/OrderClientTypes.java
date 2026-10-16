package com.notfound.logistics.orders.infrastructure.client;

import com.fasterxml.jackson.annotation.JsonAlias;
import java.math.BigDecimal;
import java.util.UUID;

public final class OrderClientTypes {
  private OrderClientTypes() {}

  public record Company(@JsonAlias("company_id") UUID id, UUID hubId) {}

  public record Product(
      @JsonAlias("product_id") UUID id, UUID companyId, String name, BigDecimal price) {}

  public record Deduction(UUID orderId, UUID hubId, UUID productId, long quantity) {}

  public record Stock(UUID id, UUID hubId, UUID productId, Long quantity) {}

  public record DeliveryRequest(
      UUID orderId,
      UUID departureHubId,
      UUID destinationHubId,
      String address,
      String recipientName) {}

  public record Delivery(UUID deliveryId, UUID orderId, String status) {}
}
