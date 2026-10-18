# 공통 개발 계약

## API·감사 계약

| 항목 | 공통 규칙 |
| --- | --- |
| 이름·입력 | Java `camelCase`, JSON·쿼리 `snake_case`. 포장 없는 DTO·`@Valid`, 잘못된 형식·검증은 400 |
| 성공 | `ApiResponse<T>.data`. 조회·수정 200, 생성 201(가능하면 Location), 본문 없음 204 |
| 오류 | 직접 `ApiErrorResponse`: `code`, `message`, `trace_id`, `field_errors`. HTTP 상태도 4xx/5xx |
| 오류 필드 | trace_id는 추적 ID 또는 null, field_errors는 `{field, message}` 목록 또는 빈 배열. 객체 오류의 field는 `$` |
| 목록 | page ≥ 0, size 1~100, 기본 page=0·size=20. 허용 필드만 정렬, 기본 `created_at,desc`에 PK 정렬 추가 |
| 페이지 | `data: {content, page, size, total_elements, total_pages}`. 빈 결과·마지막 페이지 초과는 200·빈 content |
| 감사 | `AuditEntity` 상속, `p_` 테이블, `created/updated/deleted_at`·`created/updated/deleted_by`. 시각은 UTC Instant, 작업자는 username |
| 논리 삭제 | 인증·소유권 확인 후 트랜잭션에서 `markDeleted`. 최초 생성·삭제 기록 보존, `repository.delete*` 물리 삭제 금지 |
| 조회 제외 | 각 구체 엔티티에 `@SQLRestriction("deleted_at IS NULL")`. 상속만으로는 제외되지 않음 |

업무 오류는 `domain.exception`, HTTP 상태·`ApiErrorCode`·변환은 `presentation.exception`에 둡니다.
감사는 `@EnableJpaAuditing`·`AuditorAware<String>`로 검증된 사용자/정해진 시스템 주체를 사용합니다. 주체가 없으면 실패하며 임의 system 대체는 금지합니다.
오류에 비밀값·내부 예외를 노출하지 않습니다. 예상 밖 오류는 `INTERNAL_ERROR`, 상세는 서버 로그에 남깁니다.
Actuator·Swagger/OpenAPI·Eureka·인프라 점검은 공통 응답 포장에서 제외합니다.

## 서비스 간 내부 API 계약

`/internal/**`는 Gateway 외부 라우트·Swagger에 공개하지 않습니다. 새 API는 수신 서비스의 허용 목록과 함께 갱신합니다.

| 수신자 | 메서드·경로 (`/internal` 이하) | 허용 발신자 |
| --- | --- | --- |
| Users | `GET /users/{id}/authorization` | Gateway |
| Company | `GET /companies?name=` | Users |
| Company | `GET /companies/{id}` | Orders, Users |
| Company | `GET /products/{id}` | Orders, Hub |
| Hub | `GET /hubs?name=` | Users |
| Hub | `GET /hubs/{id}` | Users, Company, Delivery |
| Hub | `GET /hub-routes?departure_hub_id=&arrival_hub_id=` | Delivery |
| Hub | `GET /hubs/{hubId}/stocks/{productId}` | Orders |
| Hub | `POST /stocks/deductions`, `POST /stocks/restorations` | Orders |
| Delivery | `POST /deliveries`, `GET /deliveries/by-order/{order_id}` | Orders |
| Delivery | `POST /deliveries/{id}/cancel`, `DELETE /deliveries/{id}` | Orders |

배송 생성은 최초 201·같은 주문/내용 200·다른 내용 409, 취소는 최초·반복 200·출발 후 409입니다.
배송 삭제는 최초·반복 204·진행 중 409, 주문별 배송 조회는 없거나 삭제됐으면 404입니다.
차감의 `order_id`는 추적용이며 멱등 키가 아닙니다. 복원은 `{order_id, hub_id, product_id, quantity}`를 받아
200·`data: {id, hub_id, product_id, quantity}`를 반환합니다. 응답 quantity는 최초 복원 직후 잔량입니다.
대상 재고 없음은 404 `STOCK_NOT_FOUND`, 수량 초과는 409 `STOCK_QUANTITY_OVERFLOW`입니다.

## 개발 DB 스키마

`local`은 엔티티로 자기 스키마를 `ddl-auto: update`, 기본 프로필은 `validate`합니다.
공유 초기화 SQL은 DB·스키마·계정만 준비합니다. 엔티티·테스트는 담당 모듈에서 변경합니다.
앱은 자기 DB·스키마만 사용하며 직접 만든 테이블만 자동 변경할 수 있습니다. 관리자 소유 테이블의 소유권은 별도 조정이 필요합니다.
운영용 마이그레이션 체계를 갖춘 구성은 아니며 외부 서버 배포에 사용하지 않습니다.
Delivery의 부분 UNIQUE 인덱스는 [indexes.sql](../delivery/src/main/resources/db/indexes.sql)로 만들고, 엔티티·인덱스 준비 후 개발 샘플을 적재합니다.

기존 볼륨의 권한 보완과 앱 갱신 순서는 [개발 절차](development.md#기존-볼륨의-스키마-생성-권한-보완)를 따릅니다.
