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
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class CompanyServiceTest {
  private static final UUID HUB_ID = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");
  private static final UUID MANAGER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Mock private CompanyRepository companyRepository;

  @InjectMocks private CompanyService companyService;

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
}
