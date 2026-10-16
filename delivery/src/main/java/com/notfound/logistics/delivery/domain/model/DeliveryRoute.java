package com.notfound.logistics.delivery.domain.model;

import com.notfound.logistics.common.audit.AuditEntity;
import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

/** 배송의 허브 간 이동 구간. 소속 배송을 @ManyToOne으로 참조한다. */
@Entity
@Table(name = "p_delivery_routes")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeliveryRoute extends AuditEntity {
  @Id
  @Column(name = "delivery_route_id")
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "delivery_id", nullable = false, updatable = false)
  private Delivery delivery;

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

  /** 배송 담당자, 배정전에는 null이다 */
  @Nullable
  @Column(name = "assignee_id")
  private UUID assigneeId;

  public DeliveryRoute(
      UUID id,
      Delivery delivery,
      Integer sequence,
      UUID arrivalHubId,
      UUID departureHubId,
      Integer expectedDistanceKm,
      Integer expectedDurationSeconds,
      DeliveryStatus status,
      @Nullable UUID assigneeId) {
    this.id = id;
    this.delivery = delivery;
    this.sequence = sequence;
    this.arrivalHubId = arrivalHubId;
    this.departureHubId = departureHubId;
    this.expectedDistanceKm = expectedDistanceKm;
    this.expectedDurationSeconds = expectedDurationSeconds;
    this.status = status;
    this.assigneeId = assigneeId;
  }
}
