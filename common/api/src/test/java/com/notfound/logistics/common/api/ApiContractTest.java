package com.notfound.logistics.common.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ApiContractTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void pageEnvelopeHasStableJsonAndCanBeReadByAnotherService() throws Exception {
    var response = new ApiResponse<>(new PageResponse<>(List.of("order-1"), 0, 10, 11));
    String json = mapper.writeValueAsString(response);
    assertThat(mapper.readTree(json))
        .isEqualTo(
            mapper.readTree(
                """
                {"data":{"content":["order-1"],"page":0,"size":10,"total_elements":11,"total_pages":2}}
                """));
    ApiResponse<PageResponse<String>> received = mapper.readValue(json, new TypeReference<>() {});
    assertThat(received).isEqualTo(response);
  }

  @Test
  void emptyAndLargePagesDoNotLoseCountPrecision() {
    assertThat(new PageResponse<>(List.of(), 0, 20, 0).totalPages()).isZero();
    assertThat(new PageResponse<>(List.of(), 5, 20, 11).totalPages()).isOne();
    assertThat(new PageResponse<>(List.of(), 0, 100, Long.MAX_VALUE).totalPages())
        .isEqualTo(92_233_720_368_547_759L);
  }

  @Test
  void invalidPageMetadataCannotEscapeAsAnApiResponse() {
    assertThatIllegalArgumentException().isThrownBy(() -> new PageResponse<>(List.of(), -1, 20, 0));
    assertThatIllegalArgumentException().isThrownBy(() -> new PageResponse<>(List.of(), 0, 0, 0));
    assertThatIllegalArgumentException().isThrownBy(() -> new PageResponse<>(List.of(), 0, 101, 0));
    assertThatIllegalArgumentException().isThrownBy(() -> new PageResponse<>(List.of(), 0, 20, -1));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new PageResponse<>(List.of("a", "b"), 0, 1, 2));
  }

  @Test
  void callerMutationDoesNotChangeAnAlreadyConstructedResponse() {
    var content = new ArrayList<>(List.of("order-1"));
    var errors = new ArrayList<>(List.of(new FieldViolation("user_name", "필수 값입니다.")));
    var page = new PageResponse<>(content, 0, 20, 1);
    var response = ApiErrorResponse.validation(null, errors);
    content.clear();
    errors.clear();
    assertThat(page.content()).containsExactly("order-1");
    assertThat(response.fieldErrors()).hasSize(1);
  }

  @Test
  void validationJsonIncludesPublicFieldNamesButNotRejectedValues() throws Exception {
    var response =
        ApiErrorResponse.validation(
            "trace-fixture", List.of(new FieldViolation("user_name", "필수 값입니다.")));
    String json = mapper.writeValueAsString(response);
    assertThat(mapper.readTree(json))
        .isEqualTo(
            mapper.readTree(
                """
                {"code":"INVALID_REQUEST","message":"요청 값이 올바르지 않습니다.",
                 "trace_id":"trace-fixture","field_errors":[{"field":"user_name","message":"필수 값입니다."}]}
                """));
    assertThat(mapper.readValue(json, ApiErrorResponse.class)).isEqualTo(response);
  }

  @Test
  void domainErrorCodesCanUseTheContractWithoutChangingTheSharedModule() throws Exception {
    ApiErrorCode domainCode = new FixtureError("ORDER_NOT_FOUND", "주문을 찾을 수 없습니다.", 404);
    var response = ApiErrorResponse.of(domainCode, null);
    var json = mapper.readTree(mapper.writeValueAsString(response));
    assertThat(json.path("code").asText()).isEqualTo("ORDER_NOT_FOUND");
    assertThat(json.get("trace_id").isNull()).isTrue();
    assertThat(json.get("field_errors").isEmpty()).isTrue();
    assertThat(domainCode.httpStatus()).isEqualTo(404);
  }

  private record FixtureError(String code, String message, int httpStatus)
      implements ApiErrorCode {}
}
