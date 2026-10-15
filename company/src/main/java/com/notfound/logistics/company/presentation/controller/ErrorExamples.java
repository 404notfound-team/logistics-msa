package com.notfound.logistics.company.presentation.controller;

/** Swagger 오류 응답 예시. 실제 응답과 같은 코드·메시지를 보여준다. 오류 코드나 메시지를 바꾸면 함께 고친다. */
final class ErrorExamples {
  static final String CREATE_INVALID_EXAMPLE =
      """
      {
        "code": "INVALID_REQUEST",
        "message": "요청 값이 올바르지 않습니다.",
        "trace_id": null,
        "field_errors": [{ "field": "hub_id", "message": "널이어서는 안됩니다" }]
      }
      """;
  static final String UPDATE_INVALID_EXAMPLE =
      """
      {
        "code": "INVALID_REQUEST",
        "message": "요청 값이 올바르지 않습니다.",
        "trace_id": null,
        "field_errors": [{ "field": "name", "message": "공백일 수 없습니다" }]
      }
      """;
  static final String LIST_INVALID_EXAMPLE =
      """
      {
        "code": "INVALID_REQUEST",
        "message": "요청 값이 올바르지 않습니다.",
        "trace_id": null,
        "field_errors": [{ "field": "size", "message": "100 이하여야 합니다" }]
      }
      """;
  static final String INVALID_ID_EXAMPLE =
      """
      {
        "code": "INVALID_REQUEST",
        "message": "요청 값이 올바르지 않습니다.",
        "trace_id": null,
        "field_errors": []
      }
      """;
  static final String COMPANY_NOT_FOUND_EXAMPLE =
      """
      {
        "code": "COMPANY_NOT_FOUND",
        "message": "업체를 찾을 수 없습니다.",
        "trace_id": null,
        "field_errors": []
      }
      """;

  private ErrorExamples() {}
}
