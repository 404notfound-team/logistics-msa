package com.notfound.logistics.delivery.domain.model;

import com.notfound.logistics.common.audit.AuditEntity;
import com.notfound.logistics.delivery.domain.exception.DeliveryBusinessException;
import com.notfound.logistics.delivery.domain.exception.DeliveryErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
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
  @Column(name = "delivery_manager_id")
  private UUID assigneeId;

  /** 낙관적 락 버전. 같은 배송을 동시에 수정하면 먼저 커밋한 요청만 반영되고, 나머지는 이전 상태를 기준으로 검증했으므로 커밋 시 실패한다. */
  @Version
  @ColumnDefault("0")
  @Column(name = "version", nullable = false)
  private Long version;

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

  /**
   * 배송 상태를 바꾼다. 진행 순서상 바로 다음 상태로만 바꿀 수 있으며, 되돌리거나 건너뛰면 INVALID_STATUS_TRANSITION이다.
   *
   * <p>현재와 같은 상태를 요청하면 아무것도 바꾸지 않는다. 같은 요청을 재시도해도 실패하지 않도록 하기 위함이다.
   */
  public void changeStatus(DeliveryStatus status) {
    Objects.requireNonNull(status, "status");
    if (this.status == status) {
      return;
    }
    if (!this.status.canTransitionTo(status)) {
      throw new DeliveryBusinessException(DeliveryErrorCode.INVALID_STATUS_TRANSITION);
    }
    this.status = status;
  }

  /**
   * 업체 배송 담당자를 배정한다. 이미 배정돼 있으면 새 담당자로 바꾼다.
   *
   * <p>허브 대기중(HUB_WAITING)인 배송만 배정·재배정. 허브 이동이 시작된 뒤에는 담당자를 바꿀 수 없으며 ASSIGNMENT_NOT_ALLOWED다.
   */
  public void assign(UUID assigneeId) {
    Objects.requireNonNull(assigneeId, "assigneeId");
    if (status != DeliveryStatus.HUB_WAITING) {
      throw new DeliveryBusinessException(DeliveryErrorCode.ASSIGNMENT_NOT_ALLOWED);
    }
    this.assigneeId = assigneeId;
  }

  /** userId가 이 배송의 업체 배송 담당자인지. 담당자가 배정되지 않았으면 누구도 아니다. */
  public boolean isAssignedTo(UUID userId) {
    return assigneeId != null && assigneeId.equals(userId);
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
