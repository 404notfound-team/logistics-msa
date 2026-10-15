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
  private final Clock clock;

  @Transactional
  public Company createCompany(String name, UUID hubId, String address, UUID managerUserId) {
    // TODO(auth): Gateway 인증 구현 후 요청자 정보(사용자 ID·역할)를 받아 권한을 검사한다.
    //  - MASTER: 제한 없음
    //  - 업체 관리자: manager_user_id가 본인 ID일 때만 생성 가능
    //  - 그 외 역할: 403
    // TODO(hub): hub 서비스 내부 API(Feign)로 hub_id가 실제 존재하는 허브인지 확인한다.
    //  - 경로·응답 형식은 hub 담당자와 합의 (예: GET /internal/hubs/{hubId})
    //  - 없는 허브면 CompanyError.HUB_NOT_FOUND, hub 서비스 장애면 503
    // TODO(users): manager_user_id가 업체 관리자 역할의 실제 사용자인지 users 서비스로 확인할지 팀과 결정한다.
    //  - 확인한다면 users 내부 API(Feign) 호출

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
      UUID id, String name, UUID hubId, String address, UUID managerUserId) {
    // TODO(auth): MASTER 또는 해당 업체 관리자만 수정할 수 있다. 그 외 403.
    Company company = findCompany(id);
    if (name != null) {
      company.changeName(name);
    }
    if (address != null) {
      company.changeAddress(address);
    }
    if (hubId != null) {
      // TODO(hub): 변경할 허브가 실제 존재하는지 hub 서비스로 확인한다. 없으면 CompanyError.HUB_NOT_FOUND.
      company.moveToHub(hubId);
    }
    if (managerUserId != null) {
      company.changeManager(managerUserId);
    }
    return company;
  }

  @Transactional
  public void deleteCompany(UUID id) {
    // TODO(auth): MASTER 또는 해당 업체 관리자만 삭제할 수 있다. 그 외 403.
    Company company = findCompany(id);
    // 업체와 소속 상품을 같은 작업자·시각으로 함께 논리 삭제한다.
    company.delete(currentActor(), clock.instant());
  }

  @Transactional
  public Product createProduct(UUID companyId, String name, String description, Long price) {
    // TODO(auth): MASTER 또는 해당 업체 관리자만 상품을 생성할 수 있다. 그 외 403.
    // 상품은 Company를 통해 추가하고, 업체 저장 시 함께 저장된다(cascade). ID는 커밋 시점에 채워진다.
    return findCompany(companyId).addProduct(name, description, price);
  }

  @Transactional(readOnly = true)
  public Product findProduct(UUID companyId, UUID productId) {
    return findCompany(companyId).getProduct(productId);
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
      UUID companyId, UUID productId, String name, String description, Long price) {
    // TODO(auth): MASTER 또는 해당 업체 관리자만 상품을 수정할 수 있다. 그 외 403.
    return findCompany(companyId).updateProduct(productId, name, description, price);
  }

  @Transactional
  public void deleteProduct(UUID companyId, UUID productId) {
    // TODO(auth): MASTER 또는 해당 업체 관리자만 상품을 삭제할 수 있다. 그 외 403.
    findCompany(companyId).deleteProduct(productId, currentActor(), clock.instant());
  }

  // 생성·수정 기록과 같은 작업자 출처를 쓴다. 인증 연동 시 AuditorAware만 바꾸면 삭제 기록도 함께 바뀐다.
  // 작업자를 알 수 없으면 임의 값으로 채우지 않고 실패한다(공통 계약).
  private String currentActor() {
    return auditorAware
        .getCurrentAuditor()
        .orElseThrow(() -> new IllegalStateException("감사 작업자를 확인할 수 없습니다."));
  }
}
