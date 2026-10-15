package com.notfound.logistics.company.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.notfound.logistics.company.domain.exception.CompanyError;
import com.notfound.logistics.company.domain.exception.CompanyException;
import com.notfound.logistics.company.domain.model.Company;
import com.notfound.logistics.company.domain.repository.CompanyRepository;
import com.notfound.logistics.company.domain.repository.CompanySearchCondition;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
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

@ExtendWith(MockitoExtension.class)
class CompanyServiceTest {
  private static final UUID HUB_ID = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");
  private static final UUID MANAGER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

  private static final UUID COMPANY_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID OTHER_HUB_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
  private static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");

  @Mock private CompanyRepository companyRepository;
  @Mock private AuditorAware<String> auditorAware;

  private CompanyService companyService;

  // 삭제 시각을 검증할 수 있도록 고정된 시계를 넣어 직접 생성한다.
  @BeforeEach
  void setUp() {
    companyService =
        new CompanyService(companyRepository, auditorAware, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void createCompanySavesRequestValues() {
    companyService.createCompany("테스트 물류업체", HUB_ID, "서울특별시 강남구 테헤란로 123", MANAGER_ID);

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
        companyService.createCompany("테스트 물류업체", HUB_ID, "서울특별시 강남구 테헤란로 123", MANAGER_ID);

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
    given(companyRepository.findById(COMPANY_ID)).willReturn(Optional.of(company));

    // 이름과 허브만 보내고 주소·관리자는 null로 둔다.
    Company updated = companyService.updateCompany(COMPANY_ID, "변경 업체", OTHER_HUB_ID, null, null);

    assertThat(updated.getName()).isEqualTo("변경 업체");
    assertThat(updated.getHubId()).isEqualTo(OTHER_HUB_ID);
    assertThat(updated.getAddress()).isEqualTo("기존 주소");
    assertThat(updated.getManagerUserId()).isEqualTo(MANAGER_ID);
  }

  @Test
  void updateMissingCompanyThrowsCompanyNotFound() {
    given(companyRepository.findById(COMPANY_ID)).willReturn(Optional.empty());

    assertThatThrownBy(() -> companyService.updateCompany(COMPANY_ID, "변경 업체", null, null, null))
        .isInstanceOf(CompanyException.class)
        .extracting(e -> ((CompanyException) e).getError())
        .isEqualTo(CompanyError.COMPANY_NOT_FOUND);
  }

  @Test
  void deleteCompanyRecordsActorAndTime() {
    Company company = Company.of("삭제할 업체", HUB_ID, "주소", MANAGER_ID);
    given(companyRepository.findById(COMPANY_ID)).willReturn(Optional.of(company));
    given(auditorAware.getCurrentAuditor()).willReturn(Optional.of("test-user"));

    companyService.deleteCompany(COMPANY_ID);

    // 물리 삭제가 아니라 삭제 시각·작업자만 기록한다. 도메인 저장소에는 delete 메서드가 없어 물리 삭제 호출은 컴파일 단계에서 막힌다.
    assertThat(company.getDeletedAt()).isEqualTo(NOW);
    assertThat(company.getDeletedBy()).isEqualTo("test-user");
  }

  @Test
  void deleteMissingCompanyThrowsCompanyNotFound() {
    given(companyRepository.findById(COMPANY_ID)).willReturn(Optional.empty());

    assertThatThrownBy(() -> companyService.deleteCompany(COMPANY_ID))
        .isInstanceOf(CompanyException.class)
        .extracting(e -> ((CompanyException) e).getError())
        .isEqualTo(CompanyError.COMPANY_NOT_FOUND);
  }

  @Test
  void deleteWithoutAuditorFailsWithoutDeleting() {
    Company company = Company.of("삭제할 업체", HUB_ID, "주소", MANAGER_ID);
    given(companyRepository.findById(COMPANY_ID)).willReturn(Optional.of(company));
    given(auditorAware.getCurrentAuditor()).willReturn(Optional.empty());

    // 공통 계약: 작업자를 알 수 없으면 임의 값으로 채우지 않고 실패한다.
    assertThatThrownBy(() -> companyService.deleteCompany(COMPANY_ID))
        .isInstanceOf(IllegalStateException.class);
    assertThat(company.getDeletedAt()).isNull();
  }
}
