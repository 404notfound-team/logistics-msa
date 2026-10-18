package com.notfound.logistics.company.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.notfound.logistics.company.domain.exception.CompanyError;
import com.notfound.logistics.company.domain.exception.CompanyException;
import com.notfound.logistics.company.domain.model.Company;
import com.notfound.logistics.company.domain.model.Product;
import com.notfound.logistics.company.domain.repository.CompanyRepository;
import com.notfound.logistics.company.domain.repository.CompanySearchCondition;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CompanyServiceTest {
  private static final UUID HUB_ID = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");
  private static final UUID MANAGER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

  private static final UUID COMPANY_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID OTHER_HUB_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
  private static final UUID PRODUCT_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
  private static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");
  private static final UUID OTHER_USER_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");
  // 기존 업무 테스트는 해당 업체 관리자로 요청한다.
  private static final Requester MANAGER = new Requester(MANAGER_ID, false);
  private static final Requester OTHER_USER = new Requester(OTHER_USER_ID, false);
  private static final Requester MASTER = new Requester(OTHER_USER_ID, true);

  @Mock private CompanyRepository companyRepository;
  @Mock private AuditorAware<String> auditorAware;
  @Mock private HubVerifier hubVerifier;

  private CompanyService companyService;

  // 삭제 시각을 검증할 수 있도록 고정된 시계를 넣어 직접 생성한다.
  @BeforeEach
  void setUp() {
    companyService =
        new CompanyService(
            companyRepository, auditorAware, hubVerifier, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void createCompanySavesRequestValues() {
    companyService.createCompany(MANAGER, "테스트 물류업체", HUB_ID, "서울특별시 강남구 테헤란로 123", MANAGER_ID);

    // save()에 실제로 넘어간 엔티티를 꺼내 요청 값이 그대로 담겼는지 확인한다.
    ArgumentCaptor<Company> captor = ArgumentCaptor.forClass(Company.class);
    verify(companyRepository).save(captor.capture());
    Company saved = captor.getValue();
    assertThat(saved.getName()).isEqualTo("테스트 물류업체");
    assertThat(saved.getHubId()).isEqualTo(HUB_ID);
    assertThat(saved.getAddress()).isEqualTo("서울특별시 강남구 테헤란로 123");
    assertThat(saved.getManagerUserId()).isEqualTo(MANAGER_ID);
  }

  @Test
  void createCompanyReturnsSavedCompany() {
    Company company =
        companyService.createCompany(MANAGER, "테스트 물류업체", HUB_ID, "서울특별시 강남구 테헤란로 123", MANAGER_ID);

    // 단위 테스트에서는 JPA가 동작하지 않으므로 ID와 감사 시각은 확인하지 않는다.
    assertThat(company.getName()).isEqualTo("테스트 물류업체");
    assertThat(company.getHubId()).isEqualTo(HUB_ID);
    assertThat(company.getAddress()).isEqualTo("서울특별시 강남구 테헤란로 123");
    assertThat(company.getManagerUserId()).isEqualTo(MANAGER_ID);
  }

  @Test
  void findCompanyReturnsStoredCompany() {
    UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
    Company stored = Company.of("테스트 물류업체", HUB_ID, "서울특별시 강남구 테헤란로 123", MANAGER_ID);
    given(companyRepository.findById(id)).willReturn(Optional.of(stored));

    assertThat(companyService.findCompany(id)).isSameAs(stored);
  }

  @Test
  void findMissingCompanyThrowsCompanyNotFound() {
    UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
    given(companyRepository.findById(id)).willReturn(Optional.empty());

    // 삭제된 업체도 저장소에서 조회되지 않으므로 같은 오류가 된다.
    assertThatThrownBy(() -> companyService.findCompany(id))
        .isInstanceOf(CompanyException.class)
        .extracting(e -> ((CompanyException) e).getError())
        .isEqualTo(CompanyError.COMPANY_NOT_FOUND);
  }

  @Test
  void findCompaniesByNameReturnsExactMatchesFromRepository() {
    Company company = Company.of("테스트 물류업체", HUB_ID, "주소", MANAGER_ID);
    given(companyRepository.findAllByName("테스트 물류업체")).willReturn(List.of(company));

    assertThat(companyService.findCompaniesByName("테스트 물류업체")).containsExactly(company);
  }

  @Test
  void searchCompaniesPassesConditionsToRepository() {
    Pageable pageable = PageRequest.of(1, 10);
    Page<Company> page = Page.empty(pageable);
    given(companyRepository.search(new CompanySearchCondition("물류", HUB_ID), pageable))
        .willReturn(page);

    assertThat(companyService.searchCompanies("물류", HUB_ID, pageable)).isSameAs(page);
    verify(companyRepository).search(new CompanySearchCondition("물류", HUB_ID), pageable);
  }

  @Test
  void updateCompanyChangesOnlyProvidedFields() {
    Company company = Company.of("기존 업체", HUB_ID, "기존 주소", MANAGER_ID);
    given(companyRepository.findByIdForUpdate(COMPANY_ID)).willReturn(Optional.of(company));

    // 이름과 허브만 보내고 주소·관리자는 null로 둔다.
    Company updated =
        companyService.updateCompany(MANAGER, COMPANY_ID, "변경 업체", OTHER_HUB_ID, null, null);

    assertThat(updated.getName()).isEqualTo("변경 업체");
    assertThat(updated.getHubId()).isEqualTo(OTHER_HUB_ID);
    assertThat(updated.getAddress()).isEqualTo("기존 주소");
    assertThat(updated.getManagerUserId()).isEqualTo(MANAGER_ID);
  }

  @Test
  void updateMissingCompanyThrowsCompanyNotFound() {
    given(companyRepository.findByIdForUpdate(COMPANY_ID)).willReturn(Optional.empty());

    assertThatThrownBy(
            () -> companyService.updateCompany(MANAGER, COMPANY_ID, "변경 업체", null, null, null))
        .isInstanceOf(CompanyException.class)
        .extracting(e -> ((CompanyException) e).getError())
        .isEqualTo(CompanyError.COMPANY_NOT_FOUND);
  }

  @Test
  void deleteCompanyRecordsActorAndTime() {
    Company company = Company.of("삭제할 업체", HUB_ID, "주소", MANAGER_ID);
    given(companyRepository.findByIdForUpdate(COMPANY_ID)).willReturn(Optional.of(company));
    given(auditorAware.getCurrentAuditor()).willReturn(Optional.of("test-user"));

    companyService.deleteCompany(MANAGER, COMPANY_ID);

    // 물리 삭제가 아니라 삭제 시각·작업자만 기록한다. 도메인 저장소에는 delete 메서드가 없어 물리 삭제 호출은 컴파일 단계에서 막힌다.
    assertThat(company.getDeletedAt()).isEqualTo(NOW);
    assertThat(company.getDeletedBy()).isEqualTo("test-user");
  }

  @Test
  void deleteMissingCompanyThrowsCompanyNotFound() {
    given(companyRepository.findByIdForUpdate(COMPANY_ID)).willReturn(Optional.empty());

    assertThatThrownBy(() -> companyService.deleteCompany(MANAGER, COMPANY_ID))
        .isInstanceOf(CompanyException.class)
        .extracting(e -> ((CompanyException) e).getError())
        .isEqualTo(CompanyError.COMPANY_NOT_FOUND);
  }

  @Test
  void deleteWithoutAuditorFailsWithoutDeleting() {
    Company company = Company.of("삭제할 업체", HUB_ID, "주소", MANAGER_ID);
    given(companyRepository.findByIdForUpdate(COMPANY_ID)).willReturn(Optional.of(company));
    given(auditorAware.getCurrentAuditor()).willReturn(Optional.empty());

    // 공통 계약: 작업자를 알 수 없으면 임의 값으로 채우지 않고 실패한다.
    assertThatThrownBy(() -> companyService.deleteCompany(MANAGER, COMPANY_ID))
        .isInstanceOf(IllegalStateException.class);
    assertThat(company.getDeletedAt()).isNull();
  }

  @Test
  void deleteCompanyAlsoDeletesItsProducts() {
    Company company = Company.of("삭제할 업체", HUB_ID, "주소", MANAGER_ID);
    Product product = company.addProduct("상품", null, 1000L);
    given(companyRepository.findByIdForUpdate(COMPANY_ID)).willReturn(Optional.of(company));
    given(auditorAware.getCurrentAuditor()).willReturn(Optional.of("test-user"));

    companyService.deleteCompany(MANAGER, COMPANY_ID);

    // 루트가 삭제되면 내부 상품도 같은 작업자·시각으로 삭제 기록된다.
    assertThat(product.getDeletedAt()).isEqualTo(NOW);
    assertThat(product.getDeletedBy()).isEqualTo("test-user");
  }

  @Test
  void createProductAddsProductThroughCompany() {
    Company company = Company.of("업체", HUB_ID, "주소", MANAGER_ID);
    given(companyRepository.findByIdForUpdate(COMPANY_ID)).willReturn(Optional.of(company));

    Product product = companyService.createProduct(MANAGER, COMPANY_ID, "무선 키보드", "설명", 35000L);

    assertThat(product.getCompany()).isSameAs(company);
    assertThat(product.getName()).isEqualTo("무선 키보드");
    assertThat(product.getDescription()).isEqualTo("설명");
    assertThat(product.getPrice()).isEqualTo(35000L);
    // 새 상품은 업체를 통해서만 추가되므로, 같은 업체에서 다시 찾을 수 있어야 한다.
    ReflectionTestUtils.setField(product, "id", PRODUCT_ID);
    assertThat(company.getProduct(PRODUCT_ID)).isSameAs(product);
  }

  @Test
  void createProductForMissingCompanyThrowsCompanyNotFound() {
    given(companyRepository.findByIdForUpdate(COMPANY_ID)).willReturn(Optional.empty());

    assertThatThrownBy(() -> companyService.createProduct(MANAGER, COMPANY_ID, "상품", null, 1000L))
        .isInstanceOf(CompanyException.class)
        .extracting(e -> ((CompanyException) e).getError())
        .isEqualTo(CompanyError.COMPANY_NOT_FOUND);
  }

  @Test
  void findProductOfOtherCompanyThrowsProductNotFound() {
    Company company = Company.of("업체", HUB_ID, "주소", MANAGER_ID);
    given(companyRepository.findById(COMPANY_ID)).willReturn(Optional.of(company));

    // 이 업체에 없는 상품 ID(다른 업체의 상품 포함)는 찾을 수 없다.
    assertThatThrownBy(() -> companyService.findProduct(COMPANY_ID, PRODUCT_ID))
        .isInstanceOf(CompanyException.class)
        .extracting(e -> ((CompanyException) e).getError())
        .isEqualTo(CompanyError.PRODUCT_NOT_FOUND);
  }

  @Test
  void findProductByIdReturnsProductWithoutCompanyId() {
    Company company = Company.of("업체", HUB_ID, "주소", MANAGER_ID);
    Product product = company.addProduct("무선 키보드", null, 35000L);
    given(companyRepository.findProductById(PRODUCT_ID)).willReturn(Optional.of(product));

    assertThat(companyService.findProductById(PRODUCT_ID)).isSameAs(product);
  }

  @Test
  void findMissingProductByIdThrowsProductNotFound() {
    given(companyRepository.findProductById(PRODUCT_ID)).willReturn(Optional.empty());

    assertThatThrownBy(() -> companyService.findProductById(PRODUCT_ID))
        .isInstanceOf(CompanyException.class)
        .extracting(e -> ((CompanyException) e).getError())
        .isEqualTo(CompanyError.PRODUCT_NOT_FOUND);
  }

  @Test
  void updateProductChangesOnlyProvidedFields() {
    Company company = companyWithProduct();
    given(companyRepository.findByIdForUpdate(COMPANY_ID)).willReturn(Optional.of(company));

    Product updated =
        companyService.updateProduct(MANAGER, COMPANY_ID, PRODUCT_ID, null, null, 39000L);

    assertThat(updated.getName()).isEqualTo("기존 상품");
    assertThat(updated.getDescription()).isEqualTo("기존 설명");
    assertThat(updated.getPrice()).isEqualTo(39000L);
  }

  @Test
  void deleteProductRecordsActorAndTime() {
    Company company = companyWithProduct();
    Product product = company.getProduct(PRODUCT_ID);
    given(companyRepository.findByIdForUpdate(COMPANY_ID)).willReturn(Optional.of(company));
    given(auditorAware.getCurrentAuditor()).willReturn(Optional.of("test-user"));

    companyService.deleteProduct(MANAGER, COMPANY_ID, PRODUCT_ID);

    assertThat(product.getDeletedAt()).isEqualTo(NOW);
    assertThat(product.getDeletedBy()).isEqualTo("test-user");
    // 상품만 삭제되고 업체는 그대로다.
    assertThat(company.getDeletedAt()).isNull();
  }

  @Test
  void searchProductsOfMissingCompanyThrowsCompanyNotFound() {
    given(companyRepository.findById(COMPANY_ID)).willReturn(Optional.empty());

    assertThatThrownBy(() -> companyService.searchProducts(COMPANY_ID, null, PageRequest.of(0, 20)))
        .isInstanceOf(CompanyException.class)
        .extracting(e -> ((CompanyException) e).getError())
        .isEqualTo(CompanyError.COMPANY_NOT_FOUND);
    verify(companyRepository, never()).searchProducts(any(), any());
  }

  @Test
  void masterCanCreateCompanyForAnyManager() {
    companyService.createCompany(MASTER, "테스트 물류업체", HUB_ID, "주소", MANAGER_ID);

    verify(hubVerifier).verifyExists(HUB_ID);
    verify(companyRepository).save(any());
  }

  @Test
  void userCannotCreateCompanyManagedBySomeoneElse() {
    assertAccessDenied(
        () -> companyService.createCompany(OTHER_USER, "테스트 물류업체", HUB_ID, "주소", MANAGER_ID));

    // 권한이 없으면 허브 확인이나 저장까지 가지 않는다.
    verify(hubVerifier, never()).verifyExists(any());
    verify(companyRepository, never()).save(any());
  }

  @Test
  void createCompanyWithMissingHubIsNotSaved() {
    willThrow(new CompanyException(CompanyError.HUB_NOT_FOUND))
        .given(hubVerifier)
        .verifyExists(HUB_ID);

    assertThatThrownBy(
            () -> companyService.createCompany(MANAGER, "테스트 물류업체", HUB_ID, "주소", MANAGER_ID))
        .isInstanceOf(CompanyException.class)
        .extracting(e -> ((CompanyException) e).getError())
        .isEqualTo(CompanyError.HUB_NOT_FOUND);
    verify(companyRepository, never()).save(any());
  }

  @Test
  void updateCompanyVerifiesOnlyChangedHub() {
    Company company = Company.of("기존 업체", HUB_ID, "기존 주소", MANAGER_ID);
    given(companyRepository.findByIdForUpdate(COMPANY_ID)).willReturn(Optional.of(company));

    companyService.updateCompany(MANAGER, COMPANY_ID, "변경 업체", null, null, null);

    verify(hubVerifier, never()).verifyExists(any());
  }

  @Test
  void updateCompanyToMissingHubFails() {
    willThrow(new CompanyException(CompanyError.HUB_NOT_FOUND))
        .given(hubVerifier)
        .verifyExists(OTHER_HUB_ID);

    assertThatThrownBy(
            () -> companyService.updateCompany(MANAGER, COMPANY_ID, null, OTHER_HUB_ID, null, null))
        .isInstanceOf(CompanyException.class)
        .extracting(e -> ((CompanyException) e).getError())
        .isEqualTo(CompanyError.HUB_NOT_FOUND);
    // hub 호출을 기다리는 동안 업체를 잠그지 않도록, 허브가 없으면 업체를 잠그기 전에 끝난다.
    verify(companyRepository, never()).findByIdForUpdate(any());
  }

  @Test
  void masterCanChangeCompanyManagedBySomeoneElse() {
    Company company = Company.of("기존 업체", HUB_ID, "기존 주소", MANAGER_ID);
    given(companyRepository.findByIdForUpdate(COMPANY_ID)).willReturn(Optional.of(company));

    companyService.updateCompany(MASTER, COMPANY_ID, "변경 업체", null, null, null);

    assertThat(company.getName()).isEqualTo("변경 업체");
  }

  @Test
  void otherUserCannotChangeCompanyOrItsProducts() {
    Company company = companyWithProduct();
    Product product = company.getProduct(PRODUCT_ID);
    given(companyRepository.findByIdForUpdate(COMPANY_ID)).willReturn(Optional.of(company));

    assertAccessDenied(
        () -> companyService.updateCompany(OTHER_USER, COMPANY_ID, "변경 업체", null, null, null));
    assertAccessDenied(() -> companyService.deleteCompany(OTHER_USER, COMPANY_ID));
    assertAccessDenied(
        () -> companyService.createProduct(OTHER_USER, COMPANY_ID, "상품", null, 1000L));
    assertAccessDenied(
        () -> companyService.updateProduct(OTHER_USER, COMPANY_ID, PRODUCT_ID, null, null, 1L));
    assertAccessDenied(() -> companyService.deleteProduct(OTHER_USER, COMPANY_ID, PRODUCT_ID));

    assertThat(company.getName()).isEqualTo("업체");
    assertThat(company.getDeletedAt()).isNull();
    assertThat(product.getPrice()).isEqualTo(35000L);
    assertThat(product.getDeletedAt()).isNull();
  }

  @Test
  void accessCheckOnMissingCompanyIsNotFound() {
    given(companyRepository.findByIdForUpdate(COMPANY_ID)).willReturn(Optional.empty());

    // 없는 업체는 권한과 관계없이 404로 응답한다.
    assertThatThrownBy(() -> companyService.deleteCompany(OTHER_USER, COMPANY_ID))
        .isInstanceOf(CompanyException.class)
        .extracting(e -> ((CompanyException) e).getError())
        .isEqualTo(CompanyError.COMPANY_NOT_FOUND);
  }

  private static void assertAccessDenied(ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOf(CompanyException.class)
        .extracting(e -> ((CompanyException) e).getError())
        .isEqualTo(CompanyError.COMPANY_ACCESS_DENIED);
  }

  // 저장 후 상태를 흉내 내기 위해 JPA가 채우는 상품 ID를 직접 넣는다.
  private static Company companyWithProduct() {
    Company company = Company.of("업체", HUB_ID, "주소", MANAGER_ID);
    Product product = company.addProduct("기존 상품", "기존 설명", 35000L);
    ReflectionTestUtils.setField(product, "id", PRODUCT_ID);
    return company;
  }
}
