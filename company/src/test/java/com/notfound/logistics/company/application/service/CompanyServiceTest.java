package com.notfound.logistics.company.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.notfound.logistics.company.domain.model.Company;
import com.notfound.logistics.company.domain.repository.CompanyRepository;
import com.notfound.logistics.company.presentation.dto.CompanyCreateRequest;
import com.notfound.logistics.company.presentation.dto.CompanyResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CompanyServiceTest {
  private static final UUID HUB_ID = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");

  @Mock private CompanyRepository companyRepository;

  @InjectMocks private CompanyService companyService;

  @Test
  void createCompanySavesRequestValues() {
    var request = new CompanyCreateRequest("테스트 물류업체", HUB_ID, "서울특별시 강남구 테헤란로 123", 1L);

    companyService.createCompany(request);

    // save()에 실제로 넘어간 엔티티를 꺼내 요청 값이 그대로 담겼는지 확인한다.
    ArgumentCaptor<Company> captor = ArgumentCaptor.forClass(Company.class);
    verify(companyRepository).save(captor.capture());
    Company saved = captor.getValue();
    assertThat(saved.getName()).isEqualTo("테스트 물류업체");
    assertThat(saved.getHubId()).isEqualTo(HUB_ID);
    assertThat(saved.getAddress()).isEqualTo("서울특별시 강남구 테헤란로 123");
    assertThat(saved.getManagerUserId()).isEqualTo(1L);
  }

  @Test
  void createCompanyReturnsResponseOfSavedCompany() {
    var request = new CompanyCreateRequest("테스트 물류업체", HUB_ID, "서울특별시 강남구 테헤란로 123", 1L);

    CompanyResponse response = companyService.createCompany(request);

    // 단위 테스트에서는 JPA가 동작하지 않으므로 company_id와 감사 시각은 확인하지 않는다.
    assertThat(response.name()).isEqualTo("테스트 물류업체");
    assertThat(response.hubId()).isEqualTo(HUB_ID);
    assertThat(response.address()).isEqualTo("서울특별시 강남구 테헤란로 123");
    assertThat(response.managerUserId()).isEqualTo(1L);
  }
}
