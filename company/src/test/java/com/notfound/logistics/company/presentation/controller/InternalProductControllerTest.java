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
import com.notfound.logistics.company.domain.model.Product;
import com.notfound.logistics.company.support.CompanySecurityTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

// orders가 의존하는 내부 응답 계약(ApiResponse 래핑, id·company_id·name·price, 404 오류 코드)을 확인한다.
@WebMvcTest(InternalProductController.class)
@CompanySecurityTest
class InternalProductControllerTest {
  private static final UUID COMPANY_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID PRODUCT_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
  private static final UUID HUB_ID = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");
  private static final UUID MANAGER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Autowired private MockMvc mvc;
  @MockitoBean private CompanyService companyService;

  @Test
  void getProductReturnsInternalContract() throws Exception {
    Company company = Company.of("업체", HUB_ID, "주소", MANAGER_ID);
    ReflectionTestUtils.setField(company, "id", COMPANY_ID);
    Product product = company.addProduct("무선 키보드", "블루투스 키보드", 35000L);
    ReflectionTestUtils.setField(product, "id", PRODUCT_ID);
    given(companyService.findProductById(PRODUCT_ID)).willReturn(product);

    mvc.perform(get("/internal/products/" + PRODUCT_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(PRODUCT_ID.toString()))
        .andExpect(jsonPath("$.data.company_id").value(COMPANY_ID.toString()))
        .andExpect(jsonPath("$.data.name").value("무선 키보드"))
        .andExpect(jsonPath("$.data.price").value(35000))
        // 공개 API 전용 필드는 내부 응답에 포함하지 않는다.
        .andExpect(jsonPath("$.data.product_id").doesNotExist())
        .andExpect(jsonPath("$.data.description").doesNotExist());
  }

  @Test
  void getMissingProductReturnsNotFound() throws Exception {
    // 삭제된 상품도 @SQLRestriction으로 조회되지 않아 같은 예외가 된다.
    given(companyService.findProductById(PRODUCT_ID))
        .willThrow(new CompanyException(CompanyError.PRODUCT_NOT_FOUND));

    mvc.perform(get("/internal/products/" + PRODUCT_ID))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
  }

  @Test
  void getProductWithInvalidIdReturnsBadRequest() throws Exception {
    mvc.perform(get("/internal/products/not-a-uuid"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

    verify(companyService, never()).findProductById(any());
  }
}
