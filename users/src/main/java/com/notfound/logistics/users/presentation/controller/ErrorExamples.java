package com.notfound.logistics.users.presentation.controller;

/** Swagger 오류 응답 예시. 실제 응답과 같은 코드·메시지를 보여준다. 오류 코드나 메시지를 바꾸면 함께 고친다. */
final class ErrorExamples {
  static final String SIGNUP_INVALID_EXAMPLE =
      """
      {
        "code": "INVALID_REQUEST",
        "message": "요청 값이 올바르지 않습니다.",
        "trace_id": null,
        "field_errors": [{ "field": "$", "message": "업체명 또는 허브명 중 하나만 입력해야 합니다." }]
      }
      """;
  static final String CREATE_INVALID_EXAMPLE =
      """
      {
        "code": "INVALID_REQUEST",
        "message": "요청 값이 올바르지 않습니다.",
        "trace_id": null,
        "field_errors": [{ "field": "$", "message": "업체 ID와 허브 ID는 함께 입력할 수 없습니다." }]
      }
      """;
  static final String UPDATE_INVALID_EXAMPLE =
      """
      {
        "code": "INVALID_REQUEST",
        "message": "요청 값이 올바르지 않습니다.",
        "trace_id": null,
        "field_errors": [{ "field": "name", "message": "공백일 수 없습니다." }]
      }
      """;
  static final String SIGNIN_INVALID_EXAMPLE =
      """
      {
        "code": "INVALID_REQUEST",
        "message": "요청 값이 올바르지 않습니다.",
        "trace_id": null,
        "field_errors": [{ "field": "password", "message": "공백일 수 없습니다" }]
      }
      """;
  static final String LIST_INVALID_EXAMPLE =
      """
      {
        "code": "INVALID_SORT",
        "message": "지원하지 않는 정렬 조건입니다.",
        "trace_id": null,
        "field_errors": []
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
  static final String INVALID_AFFILIATION_EXAMPLE =
      """
      {
        "code": "INVALID_AFFILIATION",
        "message": "일반 사용자는 업체 또는 허브 중 하나에만, 마스터는 최대 하나에 소속될 수 있습니다.",
        "trace_id": null,
        "field_errors": []
      }
      """;
  static final String INVALID_CREDENTIALS_EXAMPLE =
      """
      {
        "code": "INVALID_CREDENTIALS",
        "message": "아이디 또는 비밀번호가 올바르지 않습니다.",
        "trace_id": null,
        "field_errors": []
      }
      """;
  static final String INVALID_REFRESH_TOKEN_EXAMPLE =
      """
      {
        "code": "INVALID_REFRESH_TOKEN",
        "message": "로그인이 만료되었습니다. 다시 로그인해 주세요.",
        "trace_id": null,
        "field_errors": []
      }
      """;

  static final String UNAUTHORIZED_EXAMPLE =
      """
      {
        "code": "UNAUTHORIZED",
        "message": "인증이 필요합니다.",
        "trace_id": null,
        "field_errors": []
      }
      """;
  static final String FORBIDDEN_EXAMPLE =
      """
      {
        "code": "FORBIDDEN",
        "message": "접근 권한이 없습니다.",
        "trace_id": null,
        "field_errors": []
      }
      """;
  static final String USER_NOT_FOUND_EXAMPLE =
      """
      {
        "code": "USER_NOT_FOUND",
        "message": "사용자를 찾을 수 없습니다.",
        "trace_id": null,
        "field_errors": []
      }
      """;
  static final String AFFILIATION_NOT_FOUND_EXAMPLE =
      """
      {
        "code": "ENTITY_NOT_FOUND",
        "message": "입력한 업체 또는 허브를 찾을 수 없습니다.",
        "trace_id": null,
        "field_errors": []
      }
      """;
  static final String USERNAME_ALREADY_EXISTS_EXAMPLE =
      """
      {
        "code": "USERNAME_ALREADY_EXISTS",
        "message": "이미 사용 중인 아이디입니다.",
        "trace_id": null,
        "field_errors": []
      }
      """;
  static final String AFFILIATION_AMBIGUOUS_EXAMPLE =
      """
      {
        "code": "AFFILIATION_AMBIGUOUS",
        "message": "같은 이름의 업체 또는 허브가 여러 개입니다.",
        "trace_id": null,
        "field_errors": []
      }
      """;
  static final String AFFILIATION_LOOKUP_UNAVAILABLE_EXAMPLE =
      """
      {
        "code": "AFFILIATION_LOOKUP_UNAVAILABLE",
        "message": "업체 또는 허브 정보를 확인할 수 없습니다.",
        "trace_id": null,
        "field_errors": []
      }
      """;

  private ErrorExamples() {}
}
