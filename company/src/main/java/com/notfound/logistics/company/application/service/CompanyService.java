package com.notfound.logistics.company.application.service;

import com.notfound.logistics.company.domain.exception.CompanyError;
import com.notfound.logistics.company.domain.exception.CompanyException;
import com.notfound.logistics.company.domain.model.Company;
import com.notfound.logistics.company.domain.model.Product;
import com.notfound.logistics.company.domain.repository.CompanyRepository;
import com.notfound.logistics.company.domain.repository.CompanySearchCondition;
import com.notfound.logistics.company.domain.repository.ProductSearchCondition;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CompanyService {
  private final CompanyRepository companyRepository;
  private final AuditorAware<String> auditorAware;
  private final HubVerifier hubVerifier;
  private final Clock clock;

  @Transactional
  public Company createCompany(
      Requester requester, String name, UUID hubId, String address, UUID managerUserId) {
    // MASTER는 제한 없이, 그 외에는 본인을 관리자로 지정할 때만 생성할 수 있다.
    checkManages(requester, managerUserId);
    // TODO(users): manager_user_id가 실제 사용자인지 users 서비스로 확인할지 팀과 결정한다.
    //  - 확인한다면 users 내부 API(Feign) 호출
    hubVerifier.verifyExists(hubId);

    Company company = Company.of(name, hubId, address, managerUserId);
    companyRepository.save(company);
    return company;
  }

  @Transactional(readOnly = true)
  public Company findCompany(UUID id) {
    // 삭제된 업체는 엔티티의 @SQLRestriction("deleted_at IS NULL")으로 조회되지 않아 여기서 함께 404가 된다.
    return companyRepository
        .findById(id)
        .orElseThrow(() -> new CompanyException(CompanyError.COMPANY_NOT_FOUND));
  }

  @Transactional(readOnly = true)
  public Page<Company> searchCompanies(String name, UUID hubId, Pageable pageable) {
    // 목록과 전체 개수는 같은 검색·삭제 제외 조건으로 조회된다.
    return companyRepository.search(new CompanySearchCondition(name, hubId), pageable);
  }

  /** null인 값은 변경하지 않는다. 변경 내용은 트랜잭션 종료 시 저장된다. */
  @Transactional
  public Company updateCompany(
      Requester requester, UUID id, String name, UUID hubId, String address, UUID managerUserId) {
    Company company = findManagedCompany(requester, id);
    if (name != null) {
      company.changeName(name);
    }
    if (address != null) {
      company.changeAddress(address);
    }
    if (hubId != null) {
      hubVerifier.verifyExists(hubId);
      company.moveToHub(hubId);
    }
    if (managerUserId != null) {
      company.changeManager(managerUserId);
    }
    return company;
  }

  @Transactional
  public void deleteCompany(Requester requester, UUID id) {
    Company company = findManagedCompany(requester, id);
    // 업체와 소속 상품을 같은 작업자·시각으로 함께 논리 삭제한다.
    company.delete(currentActor(), clock.instant());
  }

  @Transactional
  public Product createProduct(
      Requester requester, UUID companyId, String name, String description, Long price) {
    // 상품은 Company를 통해 추가하고, 업체 저장 시 함께 저장된다(cascade). ID는 커밋 시점에 채워진다.
    return findManagedCompany(requester, companyId).addProduct(name, description, price);
  }

  @Transactional(readOnly = true)
  public Product findProduct(UUID companyId, UUID productId) {
    return findCompany(companyId).getProduct(productId);
  }

  /** 업체 ID 없이 상품을 조회한다(orders 내부 API용). 없거나 삭제된 상품은 PRODUCT_NOT_FOUND다. */
  @Transactional(readOnly = true)
  public Product findProductById(UUID productId) {
    return companyRepository
        .findProductById(productId)
        .orElseThrow(() -> new CompanyException(CompanyError.PRODUCT_NOT_FOUND));
  }

  @Transactional(readOnly = true)
  public Page<Product> searchProducts(UUID companyId, String name, Pageable pageable) {
    // 없는 업체·삭제된 업체의 상품 목록은 COMPANY_NOT_FOUND로 응답한다.
    findCompany(companyId);
    return companyRepository.searchProducts(new ProductSearchCondition(companyId, name), pageable);
  }

  /** null인 값은 변경하지 않는다. */
  @Transactional
  public Product updateProduct(
      Requester requester,
      UUID companyId,
      UUID productId,
      String name,
      String description,
      Long price) {
    return findManagedCompany(requester, companyId)
        .updateProduct(productId, name, description, price);
  }

  @Transactional
  public void deleteProduct(Requester requester, UUID companyId, UUID productId) {
    findManagedCompany(requester, companyId)
        .deleteProduct(productId, currentActor(), clock.instant());
  }

  // 업체와 그 상품의 변경은 MASTER 또는 해당 업체 관리자만 할 수 있다. 없는 업체는 권한 확인 전에 404로 응답한다.
  private Company findManagedCompany(Requester requester, UUID companyId) {
    Company company = findCompany(companyId);
    checkManages(requester, company.getManagerUserId());
    return company;
  }

  private static void checkManages(Requester requester, UUID managerUserId) {
    if (!requester.master() && !requester.manages(managerUserId)) {
      throw new CompanyException(CompanyError.COMPANY_ACCESS_DENIED);
    }
  }

  // 생성·수정 기록과 같은 작업자 출처를 쓴다. 인증 연동 시 AuditorAware만 바꾸면 삭제 기록도 함께 바뀐다.
  // 작업자를 알 수 없으면 임의 값으로 채우지 않고 실패한다(공통 계약).
  private String currentActor() {
    return auditorAware
        .getCurrentAuditor()
        .orElseThrow(() -> new IllegalStateException("감사 작업자를 확인할 수 없습니다."));
  }
}
