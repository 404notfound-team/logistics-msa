package com.notfound.logistics.users.domain.model;

import com.notfound.logistics.common.audit.AuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.SQLRestriction;

@Entity
@Table(
    name = "p_users",
    uniqueConstraints = @UniqueConstraint(name = "uk_users_username", columnNames = "username"))
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends AuditEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false, length = 10)
  private String username;

  @Column(nullable = false, length = 100)
  private String name;

  @Column(name = "password_hash", nullable = false, length = 255)
  private String passwordHash;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10)
  private UserRoleEnum role;

  @Column(name = "slack_id", nullable = false, length = 100)
  private String slackId;

  @Column(name = "company_id")
  private UUID companyId;

  @Column(name = "hub_id")
  private UUID hubId;

  @Column(name = "is_delivery_staff", nullable = false)
  private boolean deliveryStaff;

  /**
   * 낙관적 락 버전입니다. 수정·탈퇴·관리자 삭제가 겹치면 먼저 커밋한 쪽만 반영되고, 나중 쪽은 조회 시점의 값으로 덮어쓰지 못하고 실패합니다. 기본값은 기존 행에 컬럼을
   * 추가할 때 필요합니다.
   */
  @Version
  @ColumnDefault("0")
  @Column(nullable = false)
  private long version;

  private User(
      String username,
      String name,
      String passwordHash,
      UserRoleEnum role,
      String slackId,
      UUID companyId,
      UUID hubId,
      boolean deliveryStaff) {
    this.username = requireUsername(username);
    this.name = requireText(name, "name");
    this.passwordHash = requireText(passwordHash, "passwordHash");
    this.role = Objects.requireNonNull(role, "role");
    this.slackId = requireText(slackId, "slackId");
    validateAffiliation(role, companyId, hubId);
    this.companyId = companyId;
    this.hubId = hubId;
    this.deliveryStaff = deliveryStaff;
  }

  /** 공개 회원가입은 일반 사용자만 생성하며 역할과 배송 담당자 여부를 입력받지 않습니다. */
  public static User signUp(
      String username,
      String name,
      String passwordHash,
      String slackId,
      UUID companyId,
      UUID hubId) {
    return new User(
        username, name, passwordHash, UserRoleEnum.USER, slackId, companyId, hubId, false);
  }

  /** MASTER가 관리 기능으로 사용자를 만듭니다. MASTER는 소속 없이 만들 수 있습니다. */
  public static User create(
      String username,
      String name,
      String passwordHash,
      String slackId,
      UserRoleEnum role,
      UUID companyId,
      UUID hubId,
      boolean deliveryStaff) {
    return new User(username, name, passwordHash, role, slackId, companyId, hubId, deliveryStaff);
  }

  /** 변경 후의 전체 값을 받아 함께 검증하므로, 역할과 소속을 동시에 바꿔도 불변식이 유지됩니다. username은 JWT 주체라 바꾸지 않습니다. */
  public void update(
      String name,
      String slackId,
      String passwordHash,
      UserRoleEnum role,
      boolean deliveryStaff,
      UUID companyId,
      UUID hubId) {
    Objects.requireNonNull(role, "role");
    validateAffiliation(role, companyId, hubId);
    this.name = requireText(name, "name");
    this.slackId = requireText(slackId, "slackId");
    this.passwordHash = requireText(passwordHash, "passwordHash");
    this.role = role;
    this.deliveryStaff = deliveryStaff;
    this.companyId = companyId;
    this.hubId = hubId;
  }

  private static String requireText(String value, String fieldName) {
    Objects.requireNonNull(value, fieldName);
    if (value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    return value;
  }

  private static String requireUsername(String username) {
    requireText(username, "username");
    if (!username.matches("[a-z0-9]{4,10}")) {
      throw new IllegalArgumentException(
          "username must contain 4 to 10 lowercase letters or digits");
    }
    return username;
  }

  /** USER는 업체·허브 중 정확히 하나, MASTER는 하나 이하에 소속됩니다. */
  public static boolean isValidAffiliation(UserRoleEnum role, UUID companyId, UUID hubId) {
    if (companyId != null && hubId != null) {
      return false;
    }
    return role != UserRoleEnum.USER || companyId != null || hubId != null;
  }

  private static void validateAffiliation(UserRoleEnum role, UUID companyId, UUID hubId) {
    if (!isValidAffiliation(role, companyId, hubId)) {
      throw new IllegalArgumentException(
          "A user belongs to at most one of company or hub, and a regular user to exactly one");
    }
  }
}
