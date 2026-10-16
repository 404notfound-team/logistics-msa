# 개발용 샘플 데이터

Compose를 시작하면 각 앱이 `local` 프로필에서 자신의 테이블을 만든 뒤 샘플을 자동 적재합니다.
DB 초기화 파일은 DB·스키마·DB 계정만 준비합니다. Users는 `local` 프로필과 `DEV_DATA_ENABLED=true`일 때 개발 로그인 계정 3개를 추가합니다. 인증 경로는 실제 로그인·Gateway·Users 검증을 사용합니다.

```bash
bash scripts/init-env.sh
docker compose up -d --build --wait --wait-timeout 300
python3 scripts/smoke.py
python3 scripts/check-dev-data.py
```

`init-env.sh`는 기존 DB 비밀번호를 보존하고, 없거나 비어 있는 개발용 `JWT_SECRET`·`GATEWAY_SERVICE_TOKEN`·`USERS_SERVICE_TOKEN`·`ORDERS_SERVICE_TOKEN`·`DEV_DATA_PASSWORD`만 생성합니다.
이미 사용 중인 키도 보존합니다. Compose 자체 파서로 `export KEY=...`, `KEY = ...`, 인용·변수 참조·중복 선언의 실효 값을 확인하며, 값이 있으면 파일을 다시 쓰지 않습니다. 서비스별 값은 서로 다르게 만들고, 같은 이름의 값은 호출자·수신 서비스에 함께 전달합니다. 기존 `.env`에도 한 번 실행한 뒤 앱을 다시 시작하세요.
키와 토큰은 로컬 테스트 전용이며 로그나 Git에 남기지 않습니다.

## 기본으로 준비되는 시나리오

| 데이터 | 수량과 확인할 조건 | 소유 SQL |
| --- | --- | --- |
| 로그인 계정 | `devmaster`(MASTER), `devseed`(USER), `devseed2`(USER·배송 담당). 최초 비밀번호는 로컬 `.env`의 `DEV_DATA_PASSWORD` | [Users 적재](../../users/src/main/java/com/notfound/logistics/users/infrastructure/config/LocalDevUsers.java) |
| 허브·경로 | 기존 허브 17개·경로 289개, 같은 허브·다른 허브 | [기존 허브](../../hub/src/main/resources/db/seed/data.sql), [기존 경로](../../hub/src/main/resources/db/seed/hub-routes.sql) |
| 업체·상품 | 허브마다 업체 2개, 업체마다 상품 3개. 총 업체 34개·상품 102개 | [업체·상품](../../company/src/main/resources/db/seed/local-companies.sql) |
| 재고 | 상품별 100개·2개·0개, 총 102건 | [재고](../../hub/src/main/resources/db/seed/local-stocks.sql) |
| 주문 | 확정 20·취소 5·실패 5·논리 삭제 1. 두 작성자, 같은 허브·다른 허브, 날짜·상품 필터와 여러 페이지 | [주문](../../orders/src/main/resources/db/seed/local-orders.sql) |
| 배송 | 5개 상태마다 조회 가능한 배송 4개. 취소 주문의 배송 5개·삭제 주문의 배송 1개는 논리 삭제되어 조회 제외 | [배송](../../delivery/src/main/resources/db/seed/local_p_deliveries.sql) |

상품의 업체, 재고의 허브·상품, 주문의 공급·수령 업체·상품·허브, 배송의 주문·허브 ID가 서로 연결됩니다.
업체 이름·상품 이름에는 `[개발샘플]`이 있고 주소·수령인·식별자는 합성 값입니다.
취소 주문·배송 등은 조회용 상태 스냅샷이며 실제 취소 API 호출 결과를 뜻하지 않습니다.

## 바로 확인하기

예제의 공통 UUID 끝자리는 12자리 십진수 번호입니다.

| 대상 | 예약 ID 범위 |
| --- | --- |
| 업체 | `c0ffee39-0000-4000-8000-000000000001` ~ `...000000000034` |
| 상품 | `c0ffee39-0001-4000-8000-000000000001` ~ `...000000000102` |
| 재고 | `c0ffee39-0002-4000-8000-000000000001` ~ `...000000000102` |
| 주문 | `c0ffee39-0003-4000-8000-000000000001` ~ `...000000000031` |
| 배송 | `c0ffee39-0004-4000-8000-000000000001` ~ `...000000000025`, `...000000000031` |

첫 업체는 서울 허브 `00000000-0000-0000-0000-000000000001`에 속합니다.
첫 세 상품의 재고는 각각 100·2·0이며, 2개짜리 상품에 수량 3을 요청하면 재고 부족을 확인할 수 있습니다.
업체 목록은 `/api/companies`, 첫 업체의 상품은 `/api/companies/c0ffee39-0000-4000-8000-000000000001/products`에서 확인합니다.
`size=2&page=1`로 상품 페이지를 넘기거나 존재하지 않는 이름으로 빈 검색을 확인할 수 있습니다.

공개 회원가입·로그인 외의 업무 API는 Gateway에서 JWT가 필요합니다. 아래 도구는 개발 계정으로 실제 로그인해 15분짜리 JWT를 받습니다. 비밀번호는 실행 중인 Users 환경에서 메모리로만 읽고, `--token`을 명시했을 때만 JWT를 출력합니다. Swagger의 Authorize에도 사용할 수 있습니다.

```bash
TOKEN=$(python3 scripts/check-dev-data.py --token)
curl -H "Authorization: Bearer $TOKEN" 'http://localhost:8080/api/orders?status=CONFIRMED&size=2&page=1'
curl -H "Authorization: Bearer $TOKEN" 'http://localhost:8080/api/hubs/00000000-0000-0000-0000-000000000001/stocks'
```

기본 로그인 주체는 `devmaster` / `MASTER`입니다. `--role USER`는 `devseed`의 주문 15건만 보고,
`--username devseed2 --role USER`는 나머지 15건만 봅니다. 다른 작성자의 주문 상세는 403입니다.
배송 목록 `/api/deliveries?status=HUB_WAITING`은 4건이며 나머지 네 상태도 각각 4건입니다.
논리 삭제 주문 31번은 상세 404이고, 논리 삭제 배송은 검색 결과에서 제외됩니다.
업체 관리자 UUID `c0ffee39-0000-4000-8000-000000000099`는 `devseed`, 배송 담당자 UUID `...000000000098`은 `devseed2`와 연결됩니다. 개발 계정의 비밀번호·권한·논리 삭제는 재시작해도 바꾸지 않습니다. 예약 ID나 username을 다른 생성자가 사용하면 적재를 중단합니다.
이전 버전의 `dev-seed`·`dev-seed-other` 작성자 기록은 그대로 보존됩니다. 해당 주문은 MASTER로 조회할 수 있지만 새 USER 계정의 소유 주문에는 포함되지 않습니다.

## 검증 범위와 남은 연동

`check-dev-data.py`는 새 기본 데이터 기준으로 서비스 간 ID 연결·초기 재고·페이지·검색·주문 소유권·상태 필터·삭제 제외·401/403/404/400을 검사합니다.
데이터를 수정한 DB에서는 초기값 검사가 실패할 수 있습니다. 검사 명령 자체는 데이터를 변경하지 않습니다.
Gateway 포트를 바꿨다면 `--gateway-url http://localhost:포트`를 지정하세요.

Company의 Orders용 업체·상품 ID 조회 API는 최신 develop에 반영되어 있습니다. Users 회원가입에 필요한 Company 이름 조회 `/internal/companies?name=`는 미구현이라 업체 소속 가입은 현재 503입니다. 허브 소속 가입과 기본 개발 계정 로그인은 가능합니다. Delivery의 생성·조회·취소·삭제 내부 API도 아직 미구현입니다.
Orders와 Delivery의 내부 배송 상태 명칭도 아직 통합되지 않았습니다.
따라서 기본 샘플로 **실제 서비스 간 주문 생성·배송 생성·취소·삭제가 성공한다고 보장하지 않습니다**.
이 연동을 가짜 API나 Stub으로 기본 Compose에서 대신하지 않습니다.
입력 오류·권한·재고 부족·멱등·동시 처리·통신 실패·중단 복구는 해당 모듈의 기존 테스트로 검증합니다.
`PROCESSING`·`CANCELLING`·`RECOVERY_REQUIRED`는 자동 복구 작업이 실제 처리하므로 기본 조회 샘플로 고정하지 않습니다.
재고 차감·복원은 최신 Hub의 실제 내부 API와 [주문 로컬 Stub 테스트](../../orders/README.md)에서 각각 확인합니다.

## 재실행·수동 재적재·제거

- 재시작은 없는 샘플만 추가하며 기존 이름·관리자·수량·상태·논리 삭제를 덮어쓰지 않습니다.
- 예약 ID를 다른 생성자가 사용하면 해당 서비스의 샘플 적재는 롤백하고 시작을 중단합니다. 새 주문 샘플 작성자는 `devseed`·`devseed2`, 나머지 샘플 생성자는 `dev-seed`입니다. 이전 버전의 주문 생성자도 보존합니다.
- 각 서비스의 샘플은 자기 DB의 단일 트랜잭션입니다. 서비스 간 하나의 전역 트랜잭션은 아닙니다.
- 앱 시작 때마다 적재하므로 물리 삭제한 샘플은 다음 시작 때 복원됩니다. 논리 삭제 상태는 보존됩니다.
- 다음 명령은 앱이 사용하는 같은 SQL을 재실행합니다. 일반적인 기동에서는 필요하지 않습니다.

```bash
bash scripts/dev-data.sh hubs
bash scripts/dev-data.sh companies
bash scripts/dev-data.sh stocks
bash scripts/dev-data.sh orders
bash scripts/dev-data.sh deliveries
# 기존 방식처럼 관리자 UUID를 지정할 수도 있음. 이미 있는 업체의 관리자는 바꾸지 않음.
bash scripts/dev-data.sh companies YOUR_MANAGER_USER_UUID
```

`bash scripts/dev-data.sh clear-companies`는 예약 ID·생성자가 일치하는 상품과 업체만 한 트랜잭션에서 물리 삭제합니다.
일반 상품이 업체를 참조하면 전체 삭제가 롤백됩니다. 다른 서비스의 샘플은 제거하지 않으므로 전체 초기화 용도로 사용하지 마세요.
전체 재현은 데이터를 버려도 되는 전용 Compose 프로젝트의 새 볼륨에서 확인하세요. `DEV_DATA_PASSWORD`를 바꿔도 이미 생성된 개발 계정의 비밀번호는 바뀌지 않습니다.
기존 개발 데이터가 있는 볼륨을 검증 목적으로 삭제하지 않습니다.
