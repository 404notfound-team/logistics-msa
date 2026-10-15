package com.notfound.logistics.company.presentation.controller;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.notfound.logistics.company.application.service.CompanyService;
import com.notfound.logistics.company.domain.model.Company;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

// HTTP 계약(상태 코드, Location, snake_case JSON, 오류 형식)만 확인한다. 저장 로직은 CompanyServiceTest에서 확인한다.
@WebMvcTest(CompanyController.class)
class CompanyControllerTest {
  private static final UUID COMPANY_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID HUB_ID = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");
  private static final UUID MANAGER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Autowired private MockMvc mvc;
  @MockitoBean private CompanyService companyService;

  @Test
  void createCompanyReturnsCreatedWithLocation() throws Exception {
    // 저장 후 상태를 흉내 내기 위해 JPA가 채우는 ID를 직접 넣는다.
    Company company = Company.of("테스트 물류업체", HUB_ID, "서울특별시 강남구 테헤란로 123", MANAGER_ID);
    ReflectionTestUtils.setField(company, "id", COMPANY_ID);
    given(companyService.createCompany(any(), any(), any(), any())).willReturn(company);

    // 요청 JSON은 문자열로 직접 써서 snake_case 변환까지 함께 확인한다.
    mvc.perform(
            post("/api/companies")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "테스트 물류업체",
                      "hub_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                      "address": "서울특별시 강남구 테헤란로 123",
                      "manager_user_id": "22222222-2222-2222-2222-222222222222"
                    }
                    """))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "/api/companies/" + COMPANY_ID))
        .andExpect(jsonPath("$.data.company_id").value(COMPANY_ID.toString()))
        .andExpect(jsonPath("$.data.hub_id").value(HUB_ID.toString()))
        .andExpect(jsonPath("$.data.manager_user_id").value(MANAGER_ID.toString()));
  }

  @Test
  void missingRequiredFieldReturnsFieldErrors() throws Exception {
    mvc.perform(
            post("/api/companies")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "테스트 물류업체",
                      "address": "서울특별시 강남구 테헤란로 123",
                      "manager_user_id": "22222222-2222-2222-2222-222222222222"
                    }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.field_errors[0].field").value("hub_id"));

    verify(companyService, never()).createCompany(any(), any(), any(), any());
  }

  @Test
  void invalidUuidReturnsBadRequest() throws Exception {
    mvc.perform(
            post("/api/companies")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "테스트 물류업체",
                      "hub_id": "not-a-uuid",
                      "address": "서울특별시 강남구 테헤란로 123",
                      "manager_user_id": "22222222-2222-2222-2222-222222222222"
                    }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.field_errors").isEmpty());

    verify(companyService, never()).createCompany(any(), any(), any(), any());
  }

  @Test
  void camelCaseKeysAreNotAccepted() throws Exception {
    // camelCase 키는 무시되어 값이 비므로 snake_case 필드 경로로 검증 오류가 나야 한다.
    mvc.perform(
            post("/api/companies")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "테스트 물류업체",
                      "hubId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                      "address": "서울특별시 강남구 테헤란로 123",
                      "managerUserId": "22222222-2222-2222-2222-222222222222"
                    }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.field_errors[*].field")
                .value(containsInAnyOrder("hub_id", "manager_user_id")));

    verify(companyService, never()).createCompany(any(), any(), any(), any());
  }
}
