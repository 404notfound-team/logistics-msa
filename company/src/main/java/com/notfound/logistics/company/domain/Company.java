package com.notfound.logistics.company.domain;

import com.notfound.logistics.common.audit.AuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.SQLRestriction;

@Entity
@Table(name = "p_companies")
@SQLRestriction("deleted_at IS NULL")
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

  // Owned by the users service; only the identifier is stored here.
  @Column(name = "manager_user_id", nullable = false)
  private Long managerUserId;
}
