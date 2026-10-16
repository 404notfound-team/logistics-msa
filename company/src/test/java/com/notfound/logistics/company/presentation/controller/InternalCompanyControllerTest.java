package com.notfound.logistics.company.presentation.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.notfound.logistics.company.application.service.CompanyService;
import com.notfound.logistics.company.domain.exception.CompanyError;
import com.notfound.logistics.company.domain.exception.CompanyException;
import com.notfound.logistics.company.domain.model.Company;
import com.notfound.logistics.company.support.CompanySecurityTest;
import com.notfound.logistics.company.support.CompanySecurityTestTokens;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

// orders가 의존하는 내부 응답 계약(ApiResponse 래핑, id·snake_case 필드, 404 오류 코드)을 확인한다.
@WebMvcTest(InternalCompanyController.class)
@CompanySecurityTest
class InternalCompanyControllerTest {
  private static final UUID COMPANY_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID HUB_ID = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");
  private static final UUID MANAGER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Autowired private MockMvc mvc;
  @MockitoBean private CompanyService companyService;

  @Test
  void getCompanyReturnsInternalContract() throws Exception {
    Company company = Company.of("테스트 물류업체", HUB_ID, "서울특별시 강남구 테헤란로 123", MANAGER_ID);
    ReflectionTestUtils.setField(company, "id", COMPANY_ID);
    given(companyService.findCompany(COMPANY_ID)).willReturn(company);

    mvc.perform(get("/internal/companies/" + COMPANY_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(COMPANY_ID.toString()))
        .andExpect(jsonPath("$.data.name").value("테스트 물류업체"))
        .andExpect(jsonPath("$.data.hub_id").value(HUB_ID.toString()))
        .andExpect(jsonPath("$.data.address").value("서울특별시 강남구 테헤란로 123"))
        .andExpect(jsonPath("$.data.manager_user_id").value(MANAGER_ID.toString()))
        // 공개 API 전용 필드는 내부 응답에 포함하지 않는다.
        .andExpect(jsonPath("$.data.company_id").doesNotExist())
        .andExpect(jsonPath("$.data.created_at").doesNotExist());
  }

  @Test
  void getCompaniesByNameReturnsMatchesForUsers() throws Exception {
    Company company = Company.of("테스트 물류업체", HUB_ID, "서울특별시 강남구 테헤란로 123", MANAGER_ID);
    ReflectionTestUtils.setField(company, "id", COMPANY_ID);
    given(companyService.findCompaniesByName("테스트 물류업체")).willReturn(List.of(company));

    // users는 id·name·hub_id만 읽는다.
    mvc.perform(
            get("/internal/companies")
                .param("name", "테스트 물류업체")
                .header("X-Service-Token", CompanySecurityTestTokens.USERS_TOKEN))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].id").value(COMPANY_ID.toString()))
        .andExpect(jsonPath("$.data[0].name").value("테스트 물류업체"))
        .andExpect(jsonPath("$.data[0].hub_id").value(HUB_ID.toString()));
  }

  @Test
  void getCompaniesByNameWithoutMatchReturnsEmptyList() throws Exception {
    given(companyService.findCompaniesByName("없는 업체")).willReturn(List.of());

    mvc.perform(
            get("/internal/companies")
                .param("name", "없는 업체")
                .header("X-Service-Token", CompanySecurityTestTokens.USERS_TOKEN))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").isEmpty());
  }

  @Test
  void getCompaniesWithoutNameReturnsBadRequest() throws Exception {
    mvc.perform(
            get("/internal/companies")
                .header("X-Service-Token", CompanySecurityTestTokens.USERS_TOKEN))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  void getMissingCompanyReturnsNotFound() throws Exception {
    // 삭제된 업체도 @SQLRestriction으로 조회되지 않아 같은 예외가 된다.
    given(companyService.findCompany(COMPANY_ID))
        .willThrow(new CompanyException(CompanyError.COMPANY_NOT_FOUND));

    mvc.perform(get("/internal/companies/" + COMPANY_ID))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("COMPANY_NOT_FOUND"));
  }

  @Test
  void getCompanyWithInvalidIdReturnsBadRequest() throws Exception {
    mvc.perform(get("/internal/companies/not-a-uuid"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

    verify(companyService, never()).findCompany(any());
  }
}
