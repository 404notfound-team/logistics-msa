package com.notfound.logistics.company.presentation.dto;

import com.notfound.logistics.company.domain.model.Product;
import java.util.UUID;

/**
 * 서비스 간 내부 API 응답. 공개 API 응답(ProductResponse)과 분리해 내부 계약 변경이 외부에 영향을 주지 않게 한다. 자기 ID는 id, 소속 업체는
 * company_id로 내려준다.
 */
public record InternalProductResponse(UUID id, UUID companyId, String name, Long price) {

  public static InternalProductResponse from(Product product) {
    return new InternalProductResponse(
        product.getId(), product.getCompany().getId(), product.getName(), product.getPrice());
  }
}
