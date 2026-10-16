package com.notfound.logistics.delivery.domain.model;

import com.notfound.logistics.common.audit.AuditEntity;
import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

/** 배송의 허브 간 이동 구간. 소속 배송을 @ManyToOne으로 참조하되 물리 FK는 만들지 않는다. 같은 배송 안에서 순서(sequence)는 중복될 수 없다. */
@Entity
@Table(
    name = "p_delivery_routes",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_p_delivery_routes_delivery_id_sequence",
            columnNames = {"delivery_id", "sequence"}))
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeliveryRoute extends AuditEntity {
  @Id
  @Column(name = "delivery_route_id")
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "delivery_id",
      nullable = false,
      updatable = false,
      foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
  private Delivery delivery;

  @Column(name = "sequence", nullable = false, updatable = false)
  private Integer sequence;

  @Column(name = "departure_hub_id", nullable = false)
  private UUID departureHubId;

  @Column(name = "arrival_hub_id", nullable = false)
  private UUID arrivalHubId;

  @Column(name = "expected_distance_km", nullable = false, precision = 12, scale = 3)
  private BigDecimal expectedDistanceKm;

  @Nullable
  @Column(name = "actual_distance_km", precision = 12, scale = 3)
  private BigDecimal actualDistanceKm;

  @Column(name = "expected_duration_seconds", nullable = false)
  private Long expectedDurationSeconds;

  @Nullable
  @Column(name = "actual_duration_seconds")
  private Long actualDurationSeconds;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 30)
  private DeliveryRouteStatus status;

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
      BigDecimal expectedDistanceKm,
      Long expectedDurationSeconds,
      DeliveryRouteStatus status,
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
