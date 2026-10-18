# 주문 서비스

팀 SA·구현 설계의 주문 생성·조회·취소·삭제와 자동 복구를 구현합니다.
외부 서비스 연동은 실제 서비스 검증과 테스트 내부 장애 주입으로 구분합니다.

```text
orders/
├── src/main/java/.../orders/
│   ├── presentation/        # HTTP 입력 검증·응답
│   ├── application/         # 주문 처리·트랜잭션·복구
│   ├── domain/              # 주문 상태·저장소
│   └── infrastructure/      # Feign·JWT·감사·Slack
└── src/test/                # 주문 HTTP·PostgreSQL 및 클라이언트 장애 주입 테스트
```

## API

모든 주문 API는 `Authorization: Bearer <JWT>`가 필요합니다.
Gateway 연동 시 원본 Bearer 토큰을 전달받아 Spring Security Resource Server로 검증하고, 주문별 권한을 검사합니다.
사용자 활성 상태·현재 권한 확인과 권한 변경 후 기존 토큰 거절은 Gateway의 책임이며, 실제 연동 시 검증합니다.
외부 사용자 식별 헤더는 주문 인증에 사용하지 않습니다.
컨트롤러는 `@AuthenticationPrincipal`로 검증된 JWT를 받아 필요한 사용자 정보를 서비스 인자로 전달합니다.
JWT는 users 구현과 같은 HS256, issuer `logistics-users`, subject `username`, role `USER`/`MASTER`를 사용합니다.
`JWT_SECRET`은 32 UTF-8 바이트 이상이어야 합니다. 미설정 시 주문 API는 401을 반환합니다.
`ORDERS_SERVICE_TOKEN`은 16자 이상이어야 하며, 누락·공백·짧은 값이면 기동하지 않습니다.

| API | 권한·결과 |
| --- | --- |
| `POST /api/orders` | 로그인 사용자. `Idempotency-Key` UUID 필수. 최초 확정 201, 종료된 동일 요청 200, 처리·복구 중 202 |
| `GET /api/orders/{id}` | MASTER 또는 작성자. 주문 당시 상품·가격·허브·배송 입력 반환 |
| `GET /api/orders` | MASTER 전체 / USER 본인. 상품·상태·생성 기간·페이지·정렬 검색 |
| `POST /api/orders/{id}/cancel` | MASTER 또는 작성자. 취소 완료 200, 취소 처리·복구 중 202, 취소 불가 409 |
| `DELETE /api/orders/{id}` | MASTER. 연관 배송·경로 삭제 확인 후 논리 삭제 204. 연동 결과 불명 503으로 동일 요청 재시도 |

JSON은 snake_case이며 성공은 `ApiResponse`, 오류는 현재 공통 `ApiErrorResponse`를 사용합니다.
목록은 현재 공통 `PageResponse`의 `content`, `page`, `size`, `total_elements`, `total_pages`를 반환합니다.
기본 정렬은 `created_at,desc`와 주문 ID이며 `created_at`, `order_id`, `status`, `quantity`의 `asc`/`desc`를 허용합니다.
`created_from`·`created_to`는 UTC ISO 8601 시각이며 양 끝을 포함합니다.
마지막 페이지 이후는 큰 페이지 번호도 빈 목록을 반환합니다. 실제 결과가 있는 페이지의 시작 위치가 2,147,483,647건을 넘으면 400을 반환합니다.

## 상태와 복구

```text
생성: PROCESSING → CONFIRMED / FAILED / RECOVERY_REQUIRED
취소: CONFIRMED → CANCELLING → CANCELLED / CONFIRMED / RECOVERY_REQUIRED
복구: RECOVERY_REQUIRED → 저장된 CREATE/CANCEL 흐름 재개
```

- 최초 상품명·단가·허브·배송 입력을 고정합니다. 생성 입력의 주소·수령인·요청사항은 앞뒤 공백을 제거하고 빈 요청사항은 null로 정규화합니다.
- `(created_by, idempotency_key)`로 동시 중복 생성을 막습니다. 삭제된 주문의 키도 재사용하지 않습니다.
- 외부 호출 중 DB 트랜잭션을 유지하지 않습니다. 호출 전 처리 의도와 결과 불명 가능성을 저장합니다.
- 차감은 신규 주문에서 한 번만 호출합니다. 응답 유실·5xx·잘못된 응답 또는 차감 단계의 앱 중단은 FAILED로 종료하며 자동 재차감하지 않습니다. FAILED는 재고 미차감을 보장하지 않습니다. 동일 키는 기존 주문을 반환하고 새 키도 안전한 재시도 수단이 아닙니다.
- 차감 성공을 저장한 뒤에는 배송만 복구합니다. 타임아웃·5xx 이후의 조회 404로 이전 생성 실패를 확정하지 않으며, 저장한 입력으로 멱등 배송 요청·결과 확인을 이어갑니다.
- 배송 생성 실패가 확정되면 502와 FAILED로 끝냅니다. 재고 차감 결과 불명은 503과 FAILED로 끝냅니다.
- 취소 요청은 먼저 CANCELLING·CANCEL 의도와 요청자를 저장합니다. 배송 CANCELLED를 확인한 뒤 복원 대기 지점(`last_error=STOCK_RESTORATION_PENDING`)을 커밋하고, Hub에 주문 ID·최초 출발 허브·상품·수량으로 복원을 요청합니다. 유효한 복원 성공 응답을 확인한 뒤에만 CANCELLED로 저장합니다.
- 배송 출발이 먼저 확정되면 취소를 거절하고 복원하지 않습니다. 배송 취소 결과가 불명이면 배송 결과부터 재확인합니다. 복원 대기 지점을 저장한 뒤에는 배송 취소를 반복하지 않고 같은 주문 키로 복원만 재개합니다.
- 복원 실패·응답 유실·잘못된 응답은 202와 RECOVERY_REQUIRED를 유지합니다. Hub 복원 성공 후 Orders의 마지막 저장이 실패해도 저장된 지점에서 재개합니다. Hub의 [복원 완료 기록](../docs/domain-design.md#hub--stock-설계-결정-사항)이 중복 증가를 막습니다. 이 단계에서는 주문을 삭제할 수 없습니다.
- 자동 복구는 사용자 JWT 없이 서비스 인증과 저장된 취소 요청자의 username으로 실행합니다. 같은 주문 키에 다른 복원 내용이 저장되어 있거나 대상 재고가 없으면 완료 처리하지 않으며 원인을 확인해야 합니다.
- 배송 생성 실패와 차감 결과 불명의 FAILED 주문은 자동 복원하지 않습니다. 과거 CANCELLED/FAILED 데이터를 추정해 일괄 보정하지 않습니다. Kafka와 배송 생성 실패 보상은 후속 범위입니다.
- 5초마다 최대 50건을 확인합니다. 진행 중인 처리는 건너뛰며, 조건부 상태 갱신으로 복구를 시작합니다. 로컬 Compose의 **orders 단일 인스턴스**를 전제로 합니다.
- `last_error`에는 정해진 결과 불명·실패 코드만 저장합니다. 응답·비밀값·외부 예외 메시지는 저장하지 않습니다.
- Slack은 CONFIRMED 저장 후 최초 확정에서 한 번 시도합니다. 실패로 주문을 되돌리거나 같은 키의 재요청으로 다시 보내지 않습니다. 정확히 한 번 전달을 보장하지 않습니다.

이전 설계의 공통 오류 포장·감사 UUID 대신 현재 저장소 규약의 직접 오류 응답·username 감사 기록을 적용합니다.
원래 요청자의 감사 주체는 복구 중에도 보존합니다.

## 실제 서비스 연동

Orders는 기본 Compose에서 서비스 탐색으로 Company·Hub·Delivery를 호출합니다.
`ORDERS_SERVICE_TOKEN`과 각 수신 서비스에 등록한 Orders 인증값이 같아야 합니다.
`ORDERS_COMPANY_URL`, `ORDERS_STOCK_URL`, `ORDERS_DELIVERY_URL`은 개별 실행 시 실제 서비스 주소를 지정하는 선택 설정입니다.

배송 내부 API의 경로·요청·응답은 [생성 PR #77](https://github.com/404notfound-team/logistics-msa/pull/77),
[주문별 조회 PR #78](https://github.com/404notfound-team/logistics-msa/pull/78),
[취소 PR #80](https://github.com/404notfound-team/logistics-msa/pull/80),
[삭제 PR #81](https://github.com/404notfound-team/logistics-msa/pull/81)의 계약과 맞춥니다.
실제 주문 흐름 검증에는 네 배송 내부 API가 모두 필요합니다. 미반영 실행본에서는 검증 도구가 주문 생성 전에 실패합니다.
취소는 배송의 `CANCELLED` 응답을 확인하며 주문의 취소 상태와 각각 저장합니다.

## 실행과 검증

Java 21과 Docker가 필요합니다. 환경 설정·Compose 시작은 저장소 README를 따릅니다.
JWT는 실제 Users 로그인으로 발급합니다. 개발 계정·샘플·발급 방법은 [개발용 샘플 데이터](../infra/dev-data/README.md)를 참고하세요.

실제 주문 생성·취소·삭제와 두 지연 커밋·Orders 재시작 시나리오는
[Java 서비스 연동 테스트](../integration-tests/README.md)에서 실행합니다.
같은 전용 Compose 스택을 공유하며 기존 Python 주문 검사 범위와 실패 시 복구 절차를 유지합니다.

```bash
./gradlew --no-daemon :orders:check :orders:bootJar
```

Orders 모듈의 Java 테스트는 실제 HTTP·독립 PostgreSQL을 사용하며 외부 클라이언트와 Slack은 테스트 안에서 대체합니다.
테스트 JWT는 로컬에서 서명하며 테스트용 외부 HTTP 서버·Python 프로세스·SQLite를 실행하지 않습니다.
타임아웃 전후 반영, 배송·복원 응답 유실, 잘못된 응답, 취소·출발 경합, 마지막 주문 저장 실패,
앱 재시작 후 복구와 원래 요청자·최초 입력 보존을 검증합니다.
Feign 인증 헤더·오류 변환은 별도 테스트로 확인합니다. 이 테스트의 성공은 실제 서비스 연동 성공을 뜻하지 않습니다.
CI는 개발 샘플·실제 주문 흐름 다음에 두 지연 커밋·Orders 재시작 시나리오를 실행합니다.
선행 배송 API가 빠진 상태에서는 이 검사를 통과할 수 없습니다.

Hub의 실제 HTTP·PostgreSQL 테스트는 중복·동시 복원, 같은 키의 다른 내용 거절,
복원 기록 저장 실패 시 수량 롤백, Hub 재시작 후 응답 재현을 확인합니다.

로컬 실행본에 적용할 때는 복원 기록을 지원하는 Hub를 먼저 갱신하고 Orders를 갱신합니다.
Hub의 `local` 프로필은 `hub.p_stock_restorations` 테이블을 엔티티에서 생성합니다.
기존 비멱등 복원 API를 제공하는 Hub에 새 Orders를 연결해 복구를 실행하면 안 됩니다.
