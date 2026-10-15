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
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
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

  private static void validateAffiliation(UserRoleEnum role, UUID companyId, UUID hubId) {
    if (companyId != null && hubId != null) {
      throw new IllegalArgumentException("A user cannot belong to both a company and a hub");
    }
    if (role == UserRoleEnum.USER && companyId == null && hubId == null) {
      throw new IllegalArgumentException("A regular user must belong to a company or a hub");
    }
  }
}
