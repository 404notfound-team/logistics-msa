package com.notfound.logistics.company.presentation.controller;

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
import com.notfound.logistics.company.domain.model.Product;
import com.notfound.logistics.company.support.CompanySecurityTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

// HTTP 계약(상태 코드, Location, snake_case JSON, 오류 형식)만 확인한다. 상품 규칙은 CompanyServiceTest에서 확인한다.
@WebMvcTest(ProductController.class)
@CompanySecurityTest
class ProductControllerTest {
  private static final UUID COMPANY_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID PRODUCT_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
  private static final UUID HUB_ID = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");
  private static final UUID MANAGER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final String PRODUCTS = "/api/companies/" + COMPANY_ID + "/products";

  @Autowired private MockMvc mvc;
  @MockitoBean private CompanyService companyService;

  @Test
  void createProductReturnsCreatedWithLocation() throws Exception {
    given(companyService.createProduct(any(), any(), any(), any())).willReturn(savedProduct());

    mvc.perform(
            post(PRODUCTS)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    { "name": "무선 키보드", "description": "블루투스 키보드", "price": 35000 }
                    """))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", PRODUCTS + "/" + PRODUCT_ID))
        .andExpect(jsonPath("$.data.product_id").value(PRODUCT_ID.toString()))
        .andExpect(jsonPath("$.data.company_id").value(COMPANY_ID.toString()))
        .andExpect(jsonPath("$.data.price").value(35000));

    verify(companyService).createProduct(eq(COMPANY_ID), eq("무선 키보드"), eq("블루투스 키보드"), eq(35000L));
  }

  @Test
  void createProductWithNegativePriceReturnsFieldError() throws Exception {
    mvc.perform(
            post(PRODUCTS)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    { "name": "무선 키보드", "price": -1 }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.field_errors[0].field").value("price"));

    verify(companyService, never()).createProduct(any(), any(), any(), any());
  }

  @Test
  void createProductForMissingCompanyReturnsNotFound() throws Exception {
    given(companyService.createProduct(any(), any(), any(), any()))
        .willThrow(new CompanyException(CompanyError.COMPANY_NOT_FOUND));

    mvc.perform(
            post(PRODUCTS)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    { "name": "무선 키보드", "price": 35000 }
                    """))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("COMPANY_NOT_FOUND"));
  }

  @Test
  void getProductsReturnsPage() throws Exception {
    given(companyService.searchProducts(any(), any(), any()))
        .willReturn(new PageImpl<>(List.of(savedProduct()), PageRequest.of(0, 20), 1));

    mvc.perform(get(PRODUCTS).param("name", "키보드"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].product_id").value(PRODUCT_ID.toString()))
        .andExpect(jsonPath("$.data.total_elements").value(1));

    verify(companyService).searchProducts(eq(COMPANY_ID), eq("키보드"), any());
  }

  @Test
  void getMissingProductReturnsNotFound() throws Exception {
    given(companyService.findProduct(COMPANY_ID, PRODUCT_ID))
        .willThrow(new CompanyException(CompanyError.PRODUCT_NOT_FOUND));

    mvc.perform(get(PRODUCTS + "/" + PRODUCT_ID))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"))
        .andExpect(jsonPath("$.message").value("상품을 찾을 수 없습니다."));
  }

  @Test
  void updateProductPassesOnlySentFields() throws Exception {
    given(companyService.updateProduct(any(), any(), any(), any(), any()))
        .willReturn(savedProduct());

    mvc.perform(
            patch(PRODUCTS + "/" + PRODUCT_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    { "price": 39000 }
                    """))
        .andExpect(status().isOk());

    // 보내지 않은 필드는 null로 전달되어 기존 값을 유지한다.
    verify(companyService)
        .updateProduct(eq(COMPANY_ID), eq(PRODUCT_ID), eq(null), eq(null), eq(39000L));
  }

  @Test
  void deleteProductReturnsNoContent() throws Exception {
    mvc.perform(delete(PRODUCTS + "/" + PRODUCT_ID))
        .andExpect(status().isNoContent())
        .andExpect(content().string(""));

    verify(companyService).deleteProduct(COMPANY_ID, PRODUCT_ID);
  }

  @Test
  void deleteMissingProductReturnsNotFound() throws Exception {
    willThrow(new CompanyException(CompanyError.PRODUCT_NOT_FOUND))
        .given(companyService)
        .deleteProduct(COMPANY_ID, PRODUCT_ID);

    mvc.perform(delete(PRODUCTS + "/" + PRODUCT_ID))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
  }

  // 저장 후 상태를 흉내 내기 위해 JPA가 채우는 업체·상품 ID를 직접 넣는다.
  private static Product savedProduct() {
    Company company = Company.of("업체", HUB_ID, "주소", MANAGER_ID);
    ReflectionTestUtils.setField(company, "id", COMPANY_ID);
    Product product = company.addProduct("무선 키보드", "블루투스 키보드", 35000L);
    ReflectionTestUtils.setField(product, "id", PRODUCT_ID);
    return product;
  }
}
