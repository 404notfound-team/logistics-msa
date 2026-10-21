# 개발용 샘플 데이터

명령은 저장소 루트에서 실행합니다. 조회는 기본 개발 스택을 사용하며, 환경 준비·포트 변경은 [개발 절차](../../docs/development.md)를 따릅니다.

## 샘플과 소유 코드

각 앱은 `local`에서 자기 DB를 적재합니다. Hub·Delivery 준비 후 일회성 `dev-data`가 허브 기준 거리·시간으로 구간을 채우고 Gateway가 시작합니다.
업무 앱에 다른 서비스 DB 자격증명을 주지 않습니다. `dev-data`의 `Exited (0)`은 정상 완료입니다.

| 샘플 | 최초 기본값·소유 코드 |
| --- | --- |
| 로그인 | devmaster MASTER, devseed USER, devseed2 USER·배송 담당. 최초 비밀번호는 `.env`의 DEV_DATA_PASSWORD ([적재](../../users/src/main/java/com/notfound/logistics/users/infrastructure/config/LocalDevUsers.java)) |
| 허브·경로 | 17개·289개 ([허브](../../hub/src/main/resources/db/seed/data.sql), [경로](../../hub/src/main/resources/db/seed/hub-routes.sql)) |
| 업체·상품·재고 | 34개·102개·102건. 상품별 재고 100·2·0 ([업체·상품](../../company/src/main/resources/db/seed/local-companies.sql), [재고](../../hub/src/main/resources/db/seed/local-stocks.sql)) |
| 주문 | 확정 20·취소 5·실패 5·논리 삭제 1. USER별 조회 가능 15건 ([주문](../../orders/src/main/resources/db/seed/local-orders.sql)) |
| 배송·구간 | 조회 가능 배송은 진행 5개 상태별 4건. 26개 배송에 구간 33개, 조회 가능 25개 ([배송](../../delivery/src/main/resources/db/seed/local-deliveries.sql), [구간](../../delivery/src/main/resources/db/seed/local-delivery-routes.sql)) |

샘플은 조회용 스냅샷이며 실제 취소 API 실행 결과가 아닙니다. 중간 허브 샘플도 최단 경로·실측값을 뜻하지 않습니다.
시간은 Hub의 분×60초입니다. 같은 허브 샘플은 거리·시간 0인 한 구간을 가집니다.
PROCESSING·CANCELLING·RECOVERY_REQUIRED는 복구 작업이 처리하므로 고정 샘플로 적재하지 않습니다.

## 예약 ID·적재 규칙

공통 UUID는 `c0ffee39-종류-4000-8000-번호`이며 번호는 12자리 십진수입니다.

| 종류 | ID 범위 |
| --- | --- |
| 업체 `0000` | 1~34. 관리자 99는 devseed, 배송 담당자 98은 devseed2 |
| 상품 `0001` / 재고 `0002` | 1~102 |
| 주문 `0003` | 1~31. 31은 논리 삭제 |
| 배송 `0004` | 1~25, 31. 21~25·31은 논리 삭제 |
| 구간 `0007` | 배송 번호×10+순서. 1번 배송은 11·12, 2번은 동일 허브, 17번은 도착·이동 구간 |

- 첫 업체는 서울 허브 `00000000-0000-0000-0000-000000000001` 소속입니다. 첫 상품 3개의 재고는 100·2·0입니다.
- `/api/companies/{id}/products`, `/api/deliveries/{id}`, `/api/hubs/{id}/stocks`로 확인합니다. 다른 작성자 주문 상세는 403, 삭제 주문 상세는 404입니다.
- 재시작은 없는 샘플만 추가하고 기존 비밀번호·역할·수량·상태·담당자·논리 삭제를 보존합니다. 예약 ID/username의 소유 충돌은 적재를 중단합니다.
- 적재는 서비스별 트랜잭션입니다. 구간은 Hub를 읽고 Delivery에 한 번에 추가하며 누락 경로·소유 충돌이면 전체 구간 적재를 중단합니다.
- 물리 삭제한 기본 샘플은 다음 앱 시작 때 복원됩니다. 구간은 `docker compose up`이나 수동 재적재가 필요하며 `restart delivery`만으로 복원되지 않습니다.
- 옛 dev-seed·dev-seed-other 주문은 보존하며 MASTER로 조회합니다. 새 USER의 소유 주문으로 바꾸지 않습니다.

## 조회

Swagger 상단에서 개발 계정으로 로그인한 뒤 서비스와 API를 선택해 실행합니다. 최초 비밀번호는 `.env`의 `DEV_DATA_PASSWORD`입니다.
`devmaster`는 전체, `devseed`·`devseed2`는 각자의 주문 15건을 조회합니다. 다른 작성자의 주문 상세는 403입니다.

로그인은 서비스 선택 간 공유되며, Swagger에서 `POST /api/users/signin`을 직접 실행해도 반환된 토큰이 자동 적용됩니다.
비밀번호·토큰은 브라우저 저장소에 보관하지 않습니다. 페이지를 새로 열거나 토큰이 만료되면(기본 15분) 다시 로그인하며, 로그아웃하면 인증이 해제됩니다.
`check-dev-data.py`는 검사 내부에서 로그인하고 비밀번호·토큰을 출력하지 않습니다.

## 재적재와 제거

```bash
bash scripts/dev-data.sh hubs
bash scripts/dev-data.sh companies
bash scripts/dev-data.sh stocks
bash scripts/dev-data.sh orders
bash scripts/dev-data.sh deliveries       # 배송 + 구간
bash scripts/dev-data.sh delivery-routes  # 구간만
```

업체 재적재는 선택적으로 관리자 UUID를 받으며 기존 관리자를 바꾸지 않습니다.
`clear-companies`는 예약 ID·생성자가 일치하는 업체·상품만 물리 삭제하고 일반 상품의 참조가 있으면 롤백합니다. 전체 초기화 명령이 아닙니다.
값을 바꾼 DB에서는 초기값 검사가 실패할 수 있습니다. 재현은 폐기 가능한 별도 프로젝트의 새 볼륨에서 합니다.
