package com.notfound.logistics.company.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** JSON 필드는 전역 snake_case 규칙을 따른다: name, description, price. */
@Schema(description = "상품 생성 요청")
public record ProductCreateRequest(
    @Schema(description = "상품명 (최대 100자)", example = "무선 키보드") @NotBlank @Size(max = 100)
        String name,
    @Schema(description = "상품 설명 (선택, 최대 1000자)", example = "블루투스 5.0 무선 키보드") @Size(max = 1000)
        String description,
    @Schema(description = "가격 (원 단위 정수, 0 이상, 소수·지수 표기 불가)", example = "35000")
        @NotNull
        @PositiveOrZero
        Long price) {}
