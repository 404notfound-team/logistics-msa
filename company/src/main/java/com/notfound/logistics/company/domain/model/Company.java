package com.notfound.logistics.company.domain.model;

import com.notfound.logistics.common.audit.AuditEntity;
import com.notfound.logistics.company.domain.exception.CompanyError;
import com.notfound.logistics.company.domain.exception.CompanyException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

  // 상품은 이 Aggregate의 내부 엔티티다. 목록을 밖으로 내보내지 않고 아래 메서드로만 다룬다.
  @Getter(AccessLevel.NONE)
  @OneToMany(mappedBy = "company", cascade = CascadeType.ALL)
  @SQLRestriction("deleted_at IS NULL")
  private List<Product> products = new ArrayList<>();

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

  /** 업체와 소속 상품을 함께 논리 삭제한다. 루트가 삭제되면 내부 상품도 남기지 않는다. */
  public void delete(String actor, Instant deletedAt) {
    products.forEach(product -> product.markDeleted(actor, deletedAt));
    markDeleted(actor, deletedAt);
  }

  public Product addProduct(String name, String description, Long price) {
    Product product = new Product(this, name, description, price);
    products.add(product);
    return product;
  }

  /** 이 업체에 속한 상품만 찾는다. 다른 업체의 상품이나 삭제된 상품은 PRODUCT_NOT_FOUND다. */
  public Product getProduct(UUID productId) {
    return products.stream()
        // 방금 추가해 아직 ID가 없는 상품도 있으므로 요청 ID 쪽에서 비교한다.
        .filter(product -> productId.equals(product.getId()))
        .findFirst()
        .orElseThrow(() -> new CompanyException(CompanyError.PRODUCT_NOT_FOUND));
  }

  /** null인 값은 변경하지 않는다. */
  public Product updateProduct(UUID productId, String name, String description, Long price) {
    Product product = getProduct(productId);
    if (name != null) {
      product.changeName(name);
    }
    if (description != null) {
      product.changeDescription(description);
    }
    if (price != null) {
      product.changePrice(price);
    }
    return product;
  }

  public void deleteProduct(UUID productId, String actor, Instant deletedAt) {
    getProduct(productId).markDeleted(actor, deletedAt);
  }
}
