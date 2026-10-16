# 주문 서비스

주문 생성·배송 결과 복구와 최초 확정 알림을 구현합니다.
다른 서비스의 미구현 내부 API는 별도 로컬 HTTP Stub으로 검증합니다.

```text
orders/
├── src/main/java/.../orders/
│   ├── presentation/        # HTTP 입력 검증·응답
│   ├── application/         # 주문 처리·트랜잭션·복구
│   ├── domain/              # 주문 상태·저장소
│   └── infrastructure/      # Feign·JWT·감사·Slack
├── src/test/                # 실제 HTTP·PostgreSQL 연동 테스트
└── stubs/server.py          # 업체·상품·재고·배송·알림 HTTP Stub
```

## API

모든 주문 API는 `Authorization: Bearer <JWT>`가 필요합니다.
JWT는 users 구현과 같은 HS256, issuer `logistics-users`, subject `username`, role `USER`/`MASTER`를 사용합니다.
`JWT_SECRET`은 32 UTF-8 바이트 이상이어야 합니다. 미설정 시 주문 API는 401을 반환합니다.

| API | 권한·결과 |
| --- | --- |
| `POST /api/orders` | 로그인 사용자. `Idempotency-Key` UUID 필수. 최초 확정 201, 종료된 동일 요청 200, 처리·복구 중 202 |

JSON은 snake_case이며 성공은 `ApiResponse`, 오류는 현재 공통 `ApiErrorResponse`를 사용합니다.

## 상태와 복구

```text
생성: PROCESSING → CONFIRMED / FAILED / RECOVERY_REQUIRED
복구: RECOVERY_REQUIRED → 저장된 CREATE 흐름 재개
```

- 최초 상품명·단가·허브·배송 입력을 고정합니다. 생성 입력의 주소·수령인·요청사항은 앞뒤 공백을 제거하고 빈 요청사항은 null로 정규화합니다.
- `(created_by, idempotency_key)`로 동시 중복 생성을 막습니다. 삭제된 주문의 키도 재사용하지 않습니다.
- 외부 호출 중 DB 트랜잭션을 유지하지 않습니다. 호출 전 처리 의도와 결과 불명 가능성을 저장합니다.
- 차감은 신규 주문에서 한 번만 호출합니다. 응답 유실·5xx·잘못된 응답 또는 차감 단계의 앱 중단은 FAILED로 종료하며 자동 재차감하지 않습니다. FAILED는 재고 미차감을 보장하지 않습니다. 동일 키는 기존 주문을 반환하고 새 키도 안전한 재시도 수단이 아닙니다.
- 차감 성공을 저장한 뒤에는 배송만 복구합니다. 타임아웃·5xx 이후의 조회 404로 이전 생성 실패를 확정하지 않으며, 저장한 입력으로 멱등 배송 요청·결과 확인을 이어갑니다.
- 배송 생성 실패가 확정되면 502와 FAILED로 끝냅니다. 재고 차감 결과 불명은 503과 FAILED로 끝냅니다.
- 실패·취소 후 차감 재고는 남습니다. 복원 조정은 향후 메시징 연동에서 구현하고 실제 수량 변경은 허브가 담당합니다. 메시징은 현재 미구현이며, 차감 결과 불명은 별도 확인이 필요합니다.
- 5초마다 최대 50건을 확인합니다. 진행 중인 처리는 건너뛰며, 조건부 상태 갱신으로 복구를 시작합니다. 로컬 Compose의 **orders 단일 인스턴스**를 전제로 합니다.
- `last_error`에는 정해진 결과 불명·실패 코드만 저장합니다. 응답·비밀값·외부 예외 메시지는 저장하지 않습니다.
- Slack은 CONFIRMED 저장 후 최초 확정에서 한 번 시도합니다. 실패로 주문을 되돌리거나 같은 키의 재요청으로 다시 보내지 않습니다. 정확히 한 번 전달을 보장하지 않습니다.

이전 설계의 공통 오류 포장·감사 UUID 대신 현재 저장소 규약의 직접 오류 응답·username 감사 기록을 적용합니다.
원래 요청자의 감사 주체는 복구 중에도 보존합니다.

## 로컬 Stub 실행

Python 3, Java 21, Docker가 필요합니다. 기존 PostgreSQL·Eureka·Zipkin은 저장소 실행법을 따릅니다.

터미널에서 **테스트용 키** 두 개를 준비합니다. 같은 값을 Orders 실행 환경에도 설정합니다.

```bash
export JWT_SECRET="$(openssl rand -base64 32)"
export ORDERS_SERVICE_TOKEN="$(openssl rand -hex 24)"
python3 orders/stubs/server.py --port 18090
```

IntelliJ의 Orders 실행 설정은 `local` 프로필과 다음 환경변수를 사용합니다.

| 변수 | 값 |
| --- | --- |
| `DB_PASSWORD` | 기존 로컬 Orders DB 비밀번호 |
| `SERVER_PORT` | `8084` |
| `JWT_SECRET` | 위에서 생성한 동일한 테스트용 키 |
| `ORDERS_SERVICE_TOKEN` | 위에서 생성한 동일한 서비스 토큰 |
| `ORDERS_COMPANY_URL` | `http://127.0.0.1:18090` |
| `ORDERS_STOCK_URL` | `http://127.0.0.1:18090` |
| `ORDERS_DELIVERY_URL` | `http://127.0.0.1:18090` |

Stub과 같은 키를 설정한 터미널에서 테스트 JWT를 발급합니다. 출력은 로컬 API 호출에만 사용합니다.

```bash
python3 orders/stubs/server.py --token alice
python3 orders/stubs/server.py --token admin --role MASTER
```

Swagger는 Orders 직접 실행 시 `http://localhost:8084/v3/api-docs`, Gateway 실행 시 기존 Swagger UI에서 확인합니다.
Stub은 Gateway 외부 라우트에 추가하지 않습니다.

```json
{
  "receiver_company_id": "147259cc-6ff6-42c7-a89a-8798c8187811",
  "supplier_company_id": "1ef5bfb1-6b53-40ef-b09a-54eb71846d73",
  "product_id": "64f1ff8f-cd59-4504-b665-ef18727a6306",
  "quantity": 3,
  "delivery_address": "서울시 송파구 테스트 주소",
  "recipient_name": "수령인"
}
```

Stub은 고정 상품·업체와 최초 재고 1,000개를 제공합니다. SQLite에 재고 수량과 배송을 보존하며, 데이터 파일은 Git에서 제외합니다.
배송 생성·취소·삭제는 멱등 처리합니다. 재고 차감은 호출할 때마다 반영하므로 주문에서 재호출하지 않습니다. 실제 업체·재고·배송 서비스 구현은 포함하지 않습니다.

Stub 내부 API와 제어 API는 `X-Service-Token: <ORDERS_SERVICE_TOKEN>`을 검사합니다.
내부 업무 호출에는 `X-Actor-Username`도 필요합니다. 이는 로컬 Stub의 인증 계약이며 실제 서비스 통합 시 담당자와 인증 계약을 맞춰야 합니다.
`GET /__stub/state`로 결과를 확인하고 `POST /__stub/control`로 아래 테스트 동작을 지정할 수 있습니다.

```json
{"action":"fault","operation":"delivery.create","remaining":1,"delay_after_ms":1500}
```

`operation`은 `stock.deduct`, `delivery.create`, `delivery.cancel`, `delivery.delete`, `slack` 등을 받습니다.
`delay_before_ms`는 반영 전, `delay_after_ms`는 커밋 후 응답 지연입니다. `status`·`code`로 확정 오류도 지정할 수 있습니다.
배송 출발은 `PATCH /api/deliveries/{delivery_id}`에 `{"status":"HUB_TRANSIT"}`를 보내 시험합니다. WAITING에서만 출발하며 취소와 동시에 요청하면 먼저 반영한 전이만 성공합니다.
기본 Feign 읽기 제한은 2초이므로 수동 타임아웃 실험에서는 지연을 3초 이상으로 설정합니다.
`{"action":"reset"}`은 **Stub 데이터만** 초기화합니다. 기본 파일은 `orders/stubs/state.sqlite`이며 `--state`로 경로를 바꿀 수 있습니다.
Slack 시험에는 `SLACK_ENABLED=true`, `SLACK_WEBHOOK_URL=http://127.0.0.1:18090/__stub/slack`을 사용합니다.
실제 서비스로 전환할 때는 각 URL·서비스 인증 설정을 변경하고 동일 계약으로 다시 연동 검증합니다.

## 검증

```bash
./gradlew --no-daemon :orders:check :orders:bootJar
```

테스트는 임시 HTTP Stub과 독립 PostgreSQL 18을 사용하고 종료 시 제거합니다.
정상·중복·동시 요청, 입력·인증, 배송 실패 시 재고 유지, 차감 응답 유실 시 재차감 금지, 배송 결과의 재확인,
앱 재기동 후 자동 복구와 최초 상품·허브·감사 주체 보존을 검증합니다.
