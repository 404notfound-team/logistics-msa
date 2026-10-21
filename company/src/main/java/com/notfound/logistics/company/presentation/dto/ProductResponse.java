package com.notfound.logistics.company.presentation.dto;

import com.notfound.logistics.company.domain.model.Product;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "상품 정보")
public record ProductResponse(
    @Schema(description = "상품 ID", example = "5b7a9c1e-3f2d-4e8a-9b6c-1d2e3f4a5b6c") UUID productId,
    @Schema(description = "소속 업체 ID", example = "7d752e7e-788b-485b-808e-fd438dfe8bae")
        UUID companyId,
    @Schema(description = "상품명", example = "무선 키보드") String name,
    @Schema(description = "상품 설명", example = "블루투스 5.0 무선 키보드") String description,
    @Schema(description = "가격 (원 단위)", example = "35000") Long price,
    @Schema(description = "생성 시각 (UTC)", example = "2026-10-16T06:31:26.904Z") Instant createdAt,
    @Schema(description = "마지막 수정 시각 (UTC)", example = "2026-10-16T06:31:26.904Z")
        Instant updatedAt) {

  public static ProductResponse from(Product product) {
    return new ProductResponse(
        product.getId(),
        product.getCompany().getId(),
        product.getName(),
        product.getDescription(),
        product.getPrice(),
        product.getCreatedAt(),
        product.getUpdatedAt());
  }
}
