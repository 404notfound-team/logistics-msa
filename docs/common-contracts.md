# 공통 API·감사 규칙

## 모듈

| 라이브러리 | 용도 | 사용 앱 |
| --- | --- | --- |
| `common:api` | 응답·오류 규약 | 업무 서비스 5개, Gateway |
| `common:audit` | JPA 감사 엔티티 | 업무 서비스 5개 |

업무별 Request/Response DTO·업무 오류·PK·인증은 각 서비스가 소유합니다. Eureka는 공통 모듈을 사용하지 않습니다.

## 요청·응답

- Java는 `camelCase`, JSON·쿼리는 `snake_case`를 사용합니다. 엔티티 대신 DTO를 주고받습니다.
- 요청은 별도 포장 없이 `@Valid`로 검증합니다. 잘못된 JSON·타입·검증 오류는 400입니다.
- 성공: `ApiResponse<T>`의 `data`에 응답 DTO를 담습니다. 조회·수정은 200, 생성은 201(가능하면 `Location` 포함), 본문 없는 성공은 204입니다.
- 오류: `ApiErrorResponse`를 직접 반환하고 HTTP 상태도 4xx/5xx로 맞춥니다. 필드는 `code`, `message`, `trace_id`, `field_errors`입니다.
- 공통 오류는 `CommonErrorCode`, 업무 오류는 서비스별 `ApiErrorCode` 구현으로 정의합니다.
- `trace_id`는 서버 추적 ID 또는 null, `field_errors`는 `{field, message}` 목록 또는 빈 배열입니다. `field`는 외부 필드 경로, 객체 전체 오류는 `$`를 사용합니다.
- 오류에 비밀번호·토큰·거절된 입력값·내부 예외를 노출하지 않습니다. 예상하지 못한 오류는 `INTERNAL_ERROR`로 응답하고 상세 내용은 로그에 남깁니다.
- Actuator·Swagger/OpenAPI·Eureka·인프라 점검은 공통 응답 포장에서 제외합니다.
- 각 서비스가 예외·인증 오류 처리기를 연결합니다. Gateway는 서비스 오류의 상태·본문을 보존하고 자체 오류에도 같은 형식을 사용합니다.

## 목록·검색

- 요청: `page`는 0부터(기본 0), `size`는 1~100(기본 20). 범위를 벗어나면 400입니다.
- 정렬: 기본 `created_at,desc`에 PK 정렬을 추가합니다. 허용한 정렬 필드만 엔티티 속성으로 변환합니다.
- 응답: `ApiResponse<PageResponse<T>>` — `content`, `page`, `size`, `total_elements`, `total_pages`. 결과가 없거나 마지막 페이지를 넘기면 200과 빈 `content`를 반환합니다.
- 목록과 전체 개수는 같은 검색·삭제 제외 조건을 적용합니다.

## 감사·삭제

업무 엔티티는 PK 없는 `AuditEntity`를 상속합니다. 업무 PK는 UUID를 기본으로 하되 사용자 PK는 사용자 명세를 따릅니다. 테이블은 `p_` 접두사를 사용합니다. 스키마 생성·갱신은 [개발 DB 스키마 정책](../README.md#개발-db-스키마)을 따릅니다.

| 필드 | 규칙 |
| --- | --- |
| `created_at`, `created_by` | 최초 생성 기록, 변경 불가 |
| `updated_at`, `updated_by` | 마지막 수정 기록, 생성 시에도 기록 |
| `deleted_at`, `deleted_by` | 최초 삭제 기록, 삭제 전에는 null |

- 시각은 UTC `Instant`/`timestamptz`, 작업자는 username `String`/`varchar(100)`입니다. 외부 시각은 ISO 8601 UTC로 표현합니다.
- 업무 서비스에 `@EnableJpaAuditing`·`AuditorAware<String>`을 등록합니다. 인증된 사용자 또는 정해진 시스템 작업자를 사용하고, 주체가 없으면 예외를 발생시킵니다. 임의 `system` 대체나 `Optional.empty()` 반환은 금지합니다.
- 인증·소유권 확인 후 트랜잭션 안에서 `markDeleted(username, 삭제시각)`를 호출합니다. 감사 주체·권한은 요청값을 신뢰하지 않으며 `repository.delete*`로 물리 삭제하지 않습니다.
- **각 구체 엔티티에** `@SQLRestriction("deleted_at IS NULL")`를 선언합니다. `AuditEntity` 상속만으로 삭제 데이터가 조회에서 제외되지는 않습니다.
