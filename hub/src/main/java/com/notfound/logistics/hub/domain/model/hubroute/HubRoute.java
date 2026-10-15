package com.notfound.logistics.hub.domain.model.hubroute;

import com.notfound.logistics.common.audit.AuditEntity;
import com.notfound.logistics.hub.domain.exception.hubroute.HubRouteBusinessException;
import com.notfound.logistics.hub.domain.exception.hubroute.HubRouteErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLRestriction("deleted_at IS NULL")
@Table(
    name = "p_hub_routes",
    schema = "hub",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uk_hub_routes_departure_arrival",
          columnNames = {"departure_hub_id", "arrival_hub_id"})
    })
public class HubRoute extends AuditEntity {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "departure_hub_id", nullable = false, updatable = false)
  private UUID departureHubId;

  @Column(name = "arrival_hub_id", nullable = false, updatable = false)
  private UUID arrivalHubId;

  @Column(name = "duration_minutes", nullable = false, updatable = false)
  private Integer durationMinutes;

  @Column(name = "distance_km", nullable = false, precision = 10, scale = 2, updatable = false)
  private BigDecimal distanceKm;

  private HubRoute(
      UUID id, UUID departureHubId, UUID arrivalHubId, int durationMinutes, BigDecimal distanceKm) {

    this.id = Objects.requireNonNull(id, "경로 ID는 필수입니다.");
    this.departureHubId = Objects.requireNonNull(departureHubId, "출발 허브 ID는 필수입니다.");
    this.arrivalHubId = Objects.requireNonNull(arrivalHubId, "도착 허브 ID는 필수입니다.");
    this.distanceKm = Objects.requireNonNull(distanceKm, "이동거리는 필수입니다.");

    if (durationMinutes < 0) {
      throw new HubRouteBusinessException(HubRouteErrorCode.INVALID_HUB_ROUTE_DURATION);
    }

    if (distanceKm.signum() < 0) {
      throw new HubRouteBusinessException(HubRouteErrorCode.INVALID_HUB_ROUTE_DISTANCE);
    }

    if (departureHubId.equals(arrivalHubId) && (durationMinutes != 0 || distanceKm.signum() != 0)) {
      throw new HubRouteBusinessException(HubRouteErrorCode.INVALID_SAME_HUB_ROUTE);
    }

    this.durationMinutes = durationMinutes;
  }

  public static HubRoute create(
      UUID departureHubId, UUID arrivalHubId, int durationMinutes, BigDecimal distanceKm) {

    return new HubRoute(
        UUID.randomUUID(), departureHubId, arrivalHubId, durationMinutes, distanceKm);
  }

  public boolean isSameHubRoute() {
    return departureHubId.equals(arrivalHubId);
  }
}
