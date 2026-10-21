package com.notfound.logistics.users.domain.exception;

/** 사용자 도메인의 업무 오류. HTTP 상태와 응답 메시지는 presentation.exception.UserErrorCode가 정한다. */
public enum UserError {
  USERNAME_ALREADY_EXISTS,
  INVALID_CREDENTIALS,
  INVALID_REFRESH_TOKEN,
  ENTITY_NOT_FOUND,
  INVALID_AFFILIATION,
  INVALID_SORT,
  AFFILIATION_AMBIGUOUS,
  AFFILIATION_LOOKUP_UNAVAILABLE,
  USER_NOT_FOUND
}
