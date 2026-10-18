# 개발용 샘플 데이터

Compose를 시작하면 각 앱이 `local` 프로필에서 자신의 테이블을 만든 뒤 샘플을 자동 적재합니다.
DB 초기화 파일은 DB·스키마·DB 계정만 준비합니다. Users는 `local` 프로필과 `DEV_DATA_ENABLED=true`일 때 개발 로그인 계정 3개를 추가합니다. 인증 경로는 실제 로그인·Gateway·Users 검증을 사용합니다.
Hub와 Delivery가 준비되면 일회성 `dev-data` 작업이 Hub의 실제 경로를 읽어 배송 구간을 채웁니다. Gateway는 이 작업이 성공한 뒤 시작합니다. 업무 앱에는 다른 서비스의 DB 자격증명을 주지 않습니다.

```bash
bash scripts/init-env.sh
docker compose up -d --build --wait --wait-timeout 300
python3 scripts/smoke.py
python3 scripts/check-dev-data.py
```

`init-env.sh`는 기존 DB 비밀번호를 보존하고, 없거나 비어 있는 개발용 `JWT_SECRET`·`GATEWAY_SERVICE_TOKEN`·`USERS_SERVICE_TOKEN`·`ORDERS_SERVICE_TOKEN`·`HUB_SERVICE_TOKEN`·`COMPANY_SERVICE_TOKEN`·`DELIVERY_SERVICE_TOKEN`·`DEV_DATA_PASSWORD`만 생성합니다.
이미 사용 중인 키도 보존합니다. Compose 자체 파서로 `export KEY=...`, `KEY = ...`, 인용·변수 참조·중복 선언의 실효 값을 확인하며, 값이 있으면 파일을 다시 쓰지 않습니다. 서비스별 값은 서로 다르게 만들고, 같은 이름의 값은 호출자·수신 서비스에 함께 전달합니다. 기존 `.env`에도 한 번 실행한 뒤 앱을 다시 시작하세요.
키와 토큰은 로컬 테스트 전용이며 로그나 Git에 남기지 않습니다.

토큰은 발신자 기준입니다. Gateway→Users는 `GATEWAY_SERVICE_TOKEN`, Users→Hub/Company는 `USERS_SERVICE_TOKEN`, Orders→Company/Hub/Delivery는 `ORDERS_SERVICE_TOKEN`, Hub→Company는 `HUB_SERVICE_TOKEN`, Company→Hub는 `COMPANY_SERVICE_TOKEN`, Delivery→Hub는 `DELIVERY_SERVICE_TOKEN`을 전달할 수 있도록 Compose에 연결합니다. Company·Delivery에도 같은 `JWT_SECRET`을 전달합니다. 이 설정은 각 서비스의 내부 API 구현이나 호출 권한을 대신하지 않습니다.

## 기본으로 준비되는 시나리오

| 데이터 | 수량과 확인할 조건 | 소유 SQL |
| --- | --- | --- |
| 로그인 계정 | `devmaster`(MASTER), `devseed`(USER), `devseed2`(USER·배송 담당). 최초 비밀번호는 로컬 `.env`의 `DEV_DATA_PASSWORD` | [Users 적재](../../users/src/main/java/com/notfound/logistics/users/infrastructure/config/LocalDevUsers.java) |
| 허브·경로 | 기존 허브 17개·경로 289개, 같은 허브·다른 허브 | [기존 허브](../../hub/src/main/resources/db/seed/data.sql), [기존 경로](../../hub/src/main/resources/db/seed/hub-routes.sql) |
| 업체·상품 | 허브마다 업체 2개, 업체마다 상품 3개. 총 업체 34개·상품 102개 | [업체·상품](../../company/src/main/resources/db/seed/local-companies.sql) |
| 재고 | 상품별 100개·2개·0개, 총 102건 | [재고](../../hub/src/main/resources/db/seed/local-stocks.sql) |
| 주문 | 확정 20·취소 5·실패 5·논리 삭제 1. 두 작성자, 같은 허브·다른 허브, 날짜·상품 필터와 여러 페이지 | [주문](../../orders/src/main/resources/db/seed/local-orders.sql) |
| 배송 | 5개 상태마다 조회 가능한 배송 4개. 취소 주문의 배송 5개·삭제 주문의 배송 1개는 논리 삭제되어 조회 제외 | [배송](../../delivery/src/main/resources/db/seed/local-deliveries.sql) |
| 배송 구간 | 26개 배송에 총 33구간(조회 가능 25·논리 삭제 8). 1구간·중간 허브를 거치는 2구간, 동일 허브의 거리/시간 0, 대기·이동·도착·취소 | [구간 계산·적재](../../delivery/src/main/resources/db/seed/local-delivery-routes.sql) |

상품의 업체, 재고의 허브·상품, 주문의 공급·수령 업체·상품·허브, 배송의 주문·허브 ID가 서로 연결됩니다.
업체 이름·상품 이름에는 `[개발샘플]`이 있고 주소·수령인·식별자는 합성 값입니다.
취소 주문·배송 등은 조회용 상태 스냅샷이며 실제 취소 API 호출 결과를 뜻하지 않습니다.
구간의 예상 거리는 Hub의 `distance_km`, 예상 시간은 `duration_minutes × 60`초입니다. 일부 다른 허브 배송은 출발·도착과 다른 첫 허브를 중간 지점으로 선택해 두 구간을 연결합니다. 최단 경로 계산이나 실측 기록은 아닙니다. 도착 구간의 실제 거리·시간은 예상값과 같은 합성 값이고, 도착 전에는 null입니다. 배송이 업체로 이동하거나 완료돼도 허브 구간은 `ARRIVED`를 유지합니다.

## 바로 확인하기

예제의 공통 UUID 끝자리는 12자리 십진수 번호입니다.

| 대상 | 예약 ID 범위 |
| --- | --- |
| 업체 | `c0ffee39-0000-4000-8000-000000000001` ~ `...000000000034` |
| 상품 | `c0ffee39-0001-4000-8000-000000000001` ~ `...000000000102` |
| 재고 | `c0ffee39-0002-4000-8000-000000000001` ~ `...000000000102` |
| 주문 | `c0ffee39-0003-4000-8000-000000000001` ~ `...000000000031` |
| 배송 | `c0ffee39-0004-4000-8000-000000000001` ~ `...000000000025`, `...000000000031` |
| 배송 구간 | `c0ffee39-0007-4000-8000-` + `(배송 번호 × 10 + 순서)`를 12자리로 채움. 예: 첫 배송은 `...000000000011`, `...000000000012` |

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
목록과 상세 `/api/deliveries/c0ffee39-0004-4000-8000-000000000001`의 `routes`에 구간이 순서대로 나옵니다. 1번은 두 대기 구간, 17번은 도착한 첫 구간과 이동 중인 두 번째 구간이며, 같은 허브인 2번은 거리·시간이 0인 한 구간입니다.
논리 삭제 주문 31번은 상세 404이고, 논리 삭제 배송은 검색 결과에서 제외됩니다.
업체 관리자 UUID `c0ffee39-0000-4000-8000-000000000099`는 `devseed`, 배송 담당자 UUID `...000000000098`은 `devseed2`와 연결됩니다. 개발 계정의 비밀번호·권한·논리 삭제는 재시작해도 바꾸지 않습니다. 예약 ID나 username을 다른 생성자가 사용하면 적재를 중단합니다.
이전 버전의 `dev-seed`·`dev-seed-other` 작성자 기록은 그대로 보존됩니다. 해당 주문은 MASTER로 조회할 수 있지만 새 USER 계정의 소유 주문에는 포함되지 않습니다.

## 검증 범위와 남은 연동

`check-dev-data.py`는 새 기본 데이터 기준으로 서비스 간 ID 연결·초기 재고·페이지·검색·주문 소유권·상태 필터·삭제 제외·401/403/404/400을 검사합니다. 배송 구간의 Hub 거리·분→초 변환·연속성·상태·담당자와 목록/상세 응답도 확인합니다.
데이터를 수정한 DB에서는 초기값 검사가 실패할 수 있습니다. 검사 명령 자체는 데이터를 변경하지 않습니다.
Gateway 포트를 바꿨다면 `--gateway-url http://localhost:포트`를 지정하세요.

Company의 내부 조회 API(업체 이름 조회·업체 단건 조회·상품 단건 조회)는 최신 develop에 반영되어 있어 업체·허브 소속 가입과 Hub의 재고 생성 상품 확인이 동작합니다.
샘플 조회의 성공은 실제 서비스 간 주문 생성·배송 생성·취소·삭제의 성공을 보장하지 않습니다.
선행 배송 API와 실제 주문 흐름의 실행 조건은 [주문 서비스](../../orders/README.md#실제-서비스-연동)를 따릅니다.
기본 Compose는 실제 서비스만 호출합니다.

Delivery 테이블은 엔티티로 생성합니다. 삭제되지 않은 배송의 주문 ID를 유일하게 유지하는 부분 UNIQUE 인덱스는 [indexes.sql](../../delivery/src/main/resources/db/indexes.sql)에 둡니다. 테스트도 같은 엔티티·인덱스를 사용하며, `local` 프로필은 그 뒤 배송 샘플을 적재합니다.

입력 오류·권한·재고 부족·멱등·동시 처리·통신 실패·중단 복구는 해당 모듈의 기존 테스트로 검증합니다.
`PROCESSING`·`CANCELLING`·`RECOVERY_REQUIRED`는 자동 복구 작업이 실제 처리하므로 기본 조회 샘플로 고정하지 않습니다.
재고 차감·복원은 최신 Hub의 실제 내부 API와 [주문 서비스 검증](../../orders/README.md#실행과-검증)에서 각각 확인합니다.

## 재실행·수동 재적재·제거

- 재시작은 없는 샘플만 추가하며 기존 이름·관리자·수량·상태·논리 삭제를 덮어쓰지 않습니다.
- 예약 ID를 다른 생성자가 사용하면 해당 서비스의 샘플 적재는 롤백하고 시작을 중단합니다. 새 주문 샘플 작성자는 `devseed`·`devseed2`, 나머지 샘플 생성자는 `dev-seed`입니다. 이전 버전의 주문 생성자도 보존합니다.
- 각 서비스의 샘플은 자기 DB의 단일 트랜잭션입니다. 서비스 간 하나의 전역 트랜잭션은 아닙니다.
- 배송 구간 적재는 Hub를 한 번 읽고 Delivery에 한 트랜잭션으로 추가합니다. 필요한 Hub 경로가 없거나 예약 ID·배송/순서가 다른 생성자에게 속하면 전체 구간 적재를 중단합니다. 오류를 고친 뒤 `docker compose up -d --wait --wait-timeout 300`으로 다시 실행할 수 있습니다.
- 각 앱의 기본 샘플은 물리 삭제하면 다음 앱 시작 때 복원됩니다. 배송 구간은 `docker compose up`이나 아래 재적재 명령으로 `dev-data` 작업을 다시 실행해야 복원됩니다. `docker compose restart delivery`만으로는 구간 작업을 실행하지 않습니다. 논리 삭제 상태는 보존됩니다.
- 다음 명령은 앱이 사용하는 같은 SQL을 재실행합니다. 일반적인 기동에서는 필요하지 않습니다.

```bash
bash scripts/dev-data.sh hubs
bash scripts/dev-data.sh companies
bash scripts/dev-data.sh stocks
bash scripts/dev-data.sh orders
bash scripts/dev-data.sh deliveries
bash scripts/dev-data.sh delivery-routes
# 기존 방식처럼 관리자 UUID를 지정할 수도 있음. 이미 있는 업체의 관리자는 바꾸지 않음.
bash scripts/dev-data.sh companies YOUR_MANAGER_USER_UUID
```

`deliveries`는 배송 본문 SQL 실행 뒤 같은 `dev-data` 작업으로 구간까지 적재합니다. `delivery-routes`는 준비된 Hub·배송을 기준으로 구간만 다시 적재합니다. 정상 종료한 `dev-data` 컨테이너의 `Exited (0)`은 완료 상태입니다. 재시작·재적재는 기존 구간의 거리·시간·상태·담당자·논리 삭제를 덮어쓰지 않습니다.

`bash scripts/dev-data.sh clear-companies`는 예약 ID·생성자가 일치하는 상품과 업체만 한 트랜잭션에서 물리 삭제합니다.
일반 상품이 업체를 참조하면 전체 삭제가 롤백됩니다. 다른 서비스의 샘플은 제거하지 않으므로 전체 초기화 용도로 사용하지 마세요.
전체 재현은 데이터를 버려도 되는 전용 Compose 프로젝트의 새 볼륨에서 확인하세요. `DEV_DATA_PASSWORD`를 바꿔도 이미 생성된 개발 계정의 비밀번호는 바뀌지 않습니다.
기존 개발 데이터가 있는 볼륨을 검증 목적으로 삭제하지 않습니다.
