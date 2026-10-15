package com.notfound.logistics.company.domain.model;

import com.notfound.logistics.common.audit.AuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

@Entity
@Table(name = "p_companies")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Company extends AuditEntity {
  static final int NAME_MAX_LENGTH = 100;
  static final int ADDRESS_MAX_LENGTH = 255;

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "company_id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "name", nullable = false, length = NAME_MAX_LENGTH)
  private String name;

  @Column(name = "hub_id", nullable = false)
  private UUID hubId;

  @Column(name = "address", nullable = false, length = ADDRESS_MAX_LENGTH)
  private String address;

  // 사용자 정보는 users 서비스가 소유하므로 여기에는 ID만 저장한다.
  @Column(name = "manager_user_id", nullable = false)
  private UUID managerUserId;

  private Company(String name, UUID hubId, String address, UUID managerUserId) {
    this.name = name;
    this.hubId = hubId;
    this.address = address;
    this.managerUserId = managerUserId;
  }

  public static Company of(String name, UUID hubId, String address, UUID managerUserId) {
    return new Company(name, hubId, address, managerUserId);
  }

  public void changeName(String name) {
    this.name = name;
  }

  public void changeAddress(String address) {
    this.address = address;
  }

  public void moveToHub(UUID hubId) {
    this.hubId = hubId;
  }

  public void changeManager(UUID managerUserId) {
    this.managerUserId = managerUserId;
  }
}
