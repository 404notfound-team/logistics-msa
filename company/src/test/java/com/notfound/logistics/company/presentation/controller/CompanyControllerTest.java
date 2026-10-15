package com.notfound.logistics.company.presentation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.notfound.logistics.company.application.service.CompanyService;
import com.notfound.logistics.company.domain.exception.CompanyError;
import com.notfound.logistics.company.domain.exception.CompanyException;
import com.notfound.logistics.company.domain.model.Company;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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

  @Test
  void unsupportedMethodKeepsAllowHeader() throws Exception {
    // 405 응답도 공통 오류 본문을 쓰되, 허용 메서드를 알려주는 Allow 헤더는 유지해야 한다.
    mvc.perform(delete("/api/companies"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(header().string("Allow", containsString("POST")))
        .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
  }

  @Test
  void unsupportedMediaTypeKeepsAcceptHeader() throws Exception {
    // 415 응답도 공통 오류 본문을 쓰되, 지원하는 요청 형식을 알려주는 Accept 헤더는 유지해야 한다.
    mvc.perform(post("/api/companies").contentType(MediaType.TEXT_PLAIN).content("name=업체"))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(header().string("Accept", containsString("application/json")))
        .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));

    verify(companyService, never()).createCompany(any(), any(), any(), any());
  }

  @Test
  void getCompanyReturnsCompany() throws Exception {
    given(companyService.findCompany(COMPANY_ID)).willReturn(savedCompany());

    mvc.perform(get("/api/companies/" + COMPANY_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.company_id").value(COMPANY_ID.toString()))
        .andExpect(jsonPath("$.data.name").value("테스트 물류업체"))
        .andExpect(jsonPath("$.data.manager_user_id").value(MANAGER_ID.toString()));
  }

  @Test
  void getMissingCompanyReturnsNotFound() throws Exception {
    given(companyService.findCompany(COMPANY_ID))
        .willThrow(new CompanyException(CompanyError.COMPANY_NOT_FOUND));

    mvc.perform(get("/api/companies/" + COMPANY_ID))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("COMPANY_NOT_FOUND"))
        .andExpect(jsonPath("$.field_errors").isEmpty());
  }

  @Test
  void getCompanyWithInvalidIdReturnsBadRequest() throws Exception {
    mvc.perform(get("/api/companies/not-a-uuid"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

    verify(companyService, never()).findCompany(any());
  }

  @Test
  void getCompaniesReturnsPageWithDefaultPaging() throws Exception {
    given(companyService.searchCompanies(any(), any(), any()))
        .willReturn(new PageImpl<>(List.of(savedCompany()), PageRequest.of(0, 20), 1));

    mvc.perform(get("/api/companies").param("name", "물류").param("hub_id", HUB_ID.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].company_id").value(COMPANY_ID.toString()))
        .andExpect(jsonPath("$.data.page").value(0))
        .andExpect(jsonPath("$.data.size").value(20))
        .andExpect(jsonPath("$.data.total_elements").value(1))
        .andExpect(jsonPath("$.data.total_pages").value(1));

    // snake_case 쿼리(hub_id)가 전달되고, 기본 page·size와 최신순 정렬이 적용되는지 확인한다.
    ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
    verify(companyService).searchCompanies(eq("물류"), eq(HUB_ID), pageable.capture());
    assertThat(pageable.getValue().getPageNumber()).isZero();
    assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
    assertThat(pageable.getValue().getSort().getOrderFor("createdAt").getDirection())
        .isEqualTo(Sort.Direction.DESC);
  }

  @Test
  void getCompaniesWithOutOfRangeSizeReturnsBadRequest() throws Exception {
    mvc.perform(get("/api/companies").param("size", "101"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.field_errors[0].field").value("size"));

    verify(companyService, never()).searchCompanies(any(), any(), any());
  }

  @Test
  void getCompaniesBeyondLastPageReturnsEmptyContent() throws Exception {
    // 마지막 페이지를 넘긴 요청(int 최대 페이지 번호 포함)도 공통 계약대로 200과 빈 content를 반환한다.
    given(companyService.searchCompanies(any(), any(), any()))
        .willReturn(new PageImpl<>(List.of(), PageRequest.of(Integer.MAX_VALUE, 20), 1));

    mvc.perform(get("/api/companies").param("page", "2147483647").param("size", "20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content").isEmpty())
        .andExpect(jsonPath("$.data.page").value(Integer.MAX_VALUE))
        .andExpect(jsonPath("$.data.total_elements").value(1));
  }

  @Test
  void updateCompanyPassesOnlySentFieldsAndReturnsCompany() throws Exception {
    given(companyService.updateCompany(any(), any(), any(), any(), any()))
        .willReturn(savedCompany());

    mvc.perform(
            patch("/api/companies/" + COMPANY_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    { "name": "변경 업체" }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.company_id").value(COMPANY_ID.toString()));

    // 보내지 않은 필드는 null로 전달되어 서비스에서 기존 값을 유지한다.
    verify(companyService).updateCompany(eq(COMPANY_ID), eq("변경 업체"), eq(null), eq(null), eq(null));
  }

  @Test
  void updateWithBlankNameReturnsFieldError() throws Exception {
    mvc.perform(
            patch("/api/companies/" + COMPANY_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    { "name": "   " }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.field_errors[0].field").value("name"));

    verify(companyService, never()).updateCompany(any(), any(), any(), any(), any());
  }

  @Test
  void updateMissingCompanyReturnsNotFound() throws Exception {
    given(companyService.updateCompany(any(), any(), any(), any(), any()))
        .willThrow(new CompanyException(CompanyError.COMPANY_NOT_FOUND));

    mvc.perform(
            patch("/api/companies/" + COMPANY_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    { "address": "서울특별시 송파구 올림픽로 300" }
                    """))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("COMPANY_NOT_FOUND"));
  }

  @Test
  void deleteCompanyReturnsNoContent() throws Exception {
    mvc.perform(delete("/api/companies/" + COMPANY_ID))
        .andExpect(status().isNoContent())
        .andExpect(content().string(""));

    verify(companyService).deleteCompany(COMPANY_ID);
  }

  @Test
  void deleteMissingCompanyReturnsNotFound() throws Exception {
    willThrow(new CompanyException(CompanyError.COMPANY_NOT_FOUND))
        .given(companyService)
        .deleteCompany(COMPANY_ID);

    mvc.perform(delete("/api/companies/" + COMPANY_ID))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("COMPANY_NOT_FOUND"));
  }

  // 저장 후 상태를 흉내 내기 위해 JPA가 채우는 ID를 직접 넣은 업체를 만든다.
  private static Company savedCompany() {
    Company company = Company.of("테스트 물류업체", HUB_ID, "서울특별시 강남구 테헤란로 123", MANAGER_ID);
    ReflectionTestUtils.setField(company, "id", COMPANY_ID);
    return company;
  }
}
