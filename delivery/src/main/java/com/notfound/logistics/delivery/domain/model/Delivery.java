package com.notfound.logistics.delivery.domain.model;

import com.notfound.logistics.common.audit.AuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

/** 주문 1건의 배송. 출발 허브에서 목적지 허브까지 이동한 뒤 업체 주소의 수령인에게 전달한다. */
@Entity
@Table(name = "p_deliveries")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Delivery extends AuditEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "delivery_id")
  private UUID id;

  @Column(name = "order_id", nullable = false, updatable = false)
  private UUID orderId;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 30)
  private DeliveryStatus status;

  @Column(name = "departure_hub_id", nullable = false)
  private UUID departureHubId;

  @Column(name = "destination_hub_id", nullable = false)
  private UUID destinationHubId;

  @Column(name = "address", nullable = false)
  private String address;

  @Column(name = "recipient_name", nullable = false, length = 100)
  private String recipientName;

  /** 업체 배송 담당 사용자 ID. 배정 전에는 null이다. */
  @Column(name = "assignee_id")
  private UUID assigneeId;

  private Delivery(
      UUID orderId,
      UUID departureHubId,
      UUID destinationHubId,
      String address,
      String recipientName) {
    this.orderId = Objects.requireNonNull(orderId, "orderId");
    this.departureHubId = Objects.requireNonNull(departureHubId, "departureHubId");
    this.destinationHubId = Objects.requireNonNull(destinationHubId, "destinationHubId");
    this.address = requireText(address, "address");
    this.recipientName = requireText(recipientName, "recipientName");
    this.status = DeliveryStatus.HUB_WAITING;
  }

  public static Delivery create(
      UUID orderId,
      UUID departureHubId,
      UUID destinationHubId,
      String address,
      String recipientName) {
    return new Delivery(orderId, departureHubId, destinationHubId, address, recipientName);
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
