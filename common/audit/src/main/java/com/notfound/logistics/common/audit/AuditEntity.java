package com.notfound.logistics.common.audit;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import java.util.Objects;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** Audit metadata only; each domain entity owns its primary key. */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class AuditEntity {
  @CreatedDate
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @CreatedBy
  @Column(name = "created_by", nullable = false, updatable = false, length = 100)
  private String createdBy;

  @LastModifiedDate
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @LastModifiedBy
  @Column(name = "updated_by", nullable = false, length = 100)
  private String updatedBy;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  @Column(name = "deleted_by", length = 100)
  private String deletedBy;

  protected AuditEntity() {}

  /** Records the first deletion. The application supplies the verified actor and its clock. */
  public void markDeleted(String username, Instant deletedAt) {
    Objects.requireNonNull(username, "username");
    Objects.requireNonNull(deletedAt, "deletedAt");
    if (username.isBlank() || username.length() > 100) {
      throw new IllegalArgumentException("username must contain 1 to 100 characters");
    }
    if (this.deletedAt == null) {
      this.deletedAt = deletedAt;
      this.deletedBy = username;
    }
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public String getCreatedBy() {
    return createdBy;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public String getUpdatedBy() {
    return updatedBy;
  }

  public Instant getDeletedAt() {
    return deletedAt;
  }

  public String getDeletedBy() {
    return deletedBy;
  }
}
