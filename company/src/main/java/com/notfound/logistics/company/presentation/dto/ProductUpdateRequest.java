package com.notfound.logistics.company.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** 보낸 필드만 변경한다. 생략하거나 null인 필드는 기존 값을 유지한다. */
@Schema(description = "상품 수정 요청 (보낸 필드만 변경)")
public record ProductUpdateRequest(
    @Schema(description = "상품명 (최대 100자)", example = "무선 키보드 2세대")
        @Size(max = 100)
        @Pattern(regexp = NOT_BLANK, message = NOT_BLANK_MESSAGE)
        String name,
    @Schema(description = "상품 설명 (최대 1000자)", example = "저소음 스위치를 적용한 무선 키보드") @Size(max = 1000)
        String description,
    @Schema(description = "가격 (원 단위, 0 이상)", example = "39000") @PositiveOrZero Long price) {

  // null은 "변경 안 함"이지만, 상품명을 보냈다면 공백만으로 이루어질 수 없다.
  private static final String NOT_BLANK = "(?s).*\\S.*";
  private static final String NOT_BLANK_MESSAGE = "공백일 수 없습니다";
}
