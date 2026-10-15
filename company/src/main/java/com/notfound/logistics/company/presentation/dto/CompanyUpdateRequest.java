package com.notfound.logistics.company.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** 보낸 필드만 변경한다. 생략하거나 null인 필드는 기존 값을 유지한다. */
@Schema(description = "업체 수정 요청 (보낸 필드만 변경)")
public record CompanyUpdateRequest(
    @Schema(description = "업체명 (최대 100자)", example = "변경된 물류업체")
        @Size(max = 100)
        @Pattern(regexp = NOT_BLANK, message = NOT_BLANK_MESSAGE)
        String name,
    @Schema(description = "관리 허브 ID", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6") UUID hubId,
    @Schema(description = "업체 주소 (최대 255자)", example = "서울특별시 송파구 올림픽로 300")
        @Size(max = 255)
        @Pattern(regexp = NOT_BLANK, message = NOT_BLANK_MESSAGE)
        String address,
    @Schema(description = "업체 관리자 사용자 ID", example = "22222222-2222-2222-2222-222222222222")
        UUID managerUserId) {

  // null은 "변경 안 함"이지만, 값을 보냈다면 공백만으로 이루어질 수 없다.
  private static final String NOT_BLANK = "(?s).*\\S.*";
  private static final String NOT_BLANK_MESSAGE = "공백일 수 없습니다";
}
