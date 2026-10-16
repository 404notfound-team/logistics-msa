package com.notfound.logistics.delivery.domain.model;

import jakarta.annotation.Nullable;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(name = "p_delivery_routes")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeliveryRoute {
  @Id
  private UUID id;

  @Column(name = "delivery_id", nullable = false, updatable = false)
  private UUID deliveryId;

  @Column(name = "sequence", nullable = false, updatable = false)
  private Integer sequence;

  @Column(name = "departure_hub_id", nullable = false)
  private UUID departureHubId;

  @Column(name = "arrival_hub_id", nullable = false)
  private UUID arrivalHubId;

  @Column(name = "expected_distance_km", nullable = false)
  private Integer expectedDistanceKm;

  @Nullable
  @Column(name = "actual_distance_km")
  private Integer actualDistanceKm;

  @Column(name = "expected_duration_seconds", nullable = false)
  private Integer expectedDurationSeconds;

  @Nullable
  @Column(name = "actual_duration_seconds")
  private Integer actualDurationSeconds;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 30)
  private DeliveryStatus status;

  /**
   * 배송 담당자, 배정전에는 null이다
   */
  @Nullable
  @Column(name = "assignee_id")
  private UUID assigneeId;

  public DeliveryRoute(
    UUID id,
    UUID deliveryId,
    Integer sequence,
    UUID arrivalHubId,
    UUID departureHubId,
    Integer expectedDistanceKm,
    Integer expectedDurationSeconds,
    DeliveryStatus status,
    @Nullable UUID assigneeId) {
    this.id = id;
    this.deliveryId = deliveryId;
    this.sequence = sequence;
    this.arrivalHubId = arrivalHubId;
    this.departureHubId = departureHubId;
    this.expectedDistanceKm = expectedDistanceKm;
    this.expectedDurationSeconds = expectedDurationSeconds;
    this.status = status;
    this.assigneeId = assigneeId;
  }
}
