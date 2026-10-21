package com.notfound.logistics.company.domain.model;

import com.notfound.logistics.common.audit.AuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

/**
 * 업체에 속한 상품. Company Aggregate의 내부 엔티티라서 생성·변경·삭제는 Company를 통해서만 한다. 그래서 생성자와 변경 메서드는 같은 패키지에만
 * 공개한다.
 */
@Entity
@Table(name = "p_products")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product extends AuditEntity {
  static final int NAME_MAX_LENGTH = 100;
  static final int DESCRIPTION_MAX_LENGTH = 1000;

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "product_id", nullable = false, updatable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "company_id", nullable = false, updatable = false)
  private Company company;

  @Column(name = "name", nullable = false, length = NAME_MAX_LENGTH)
  private String name;

  @Column(name = "description", length = DESCRIPTION_MAX_LENGTH)
  private String description;

  // 원 단위 가격. 0 이상이다.
  @Column(name = "price", nullable = false)
  private Long price;

  Product(Company company, String name, String description, Long price) {
    this.company = company;
    this.name = name;
    this.description = description;
    this.price = price;
  }

  void changeName(String name) {
    this.name = name;
  }

  void changeDescription(String description) {
    this.description = description;
  }

  void changePrice(Long price) {
    this.price = price;
  }
}
