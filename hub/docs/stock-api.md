# 재고 생성·조회 API

재고 생성은 본문으로 속성을 받고, 조회 대상 ID는 경로로 지정합니다. 페이지는 쿼리 파라미터로 받습니다. 성공·페이지·오류 응답은 [공통 규약](../../docs/common-contracts.md)을 따릅니다.

| 호출자 | Method | 경로 | 용도 |
| --- | --- | --- | --- |
| 외부 관리 사용자 | POST | `/api/stocks` | 최초 재고 등록. 본문은 `hub_id`, `product_id`, `quantity`(0 이상), 성공은 201과 외부 상세 조회 Location |
| 주문 서비스 | GET | `/internal/hubs/{hubId}/stocks/{productId}` | 주문 처리에 필요한 특정 허브·상품 재고 조회 |
| 로그인 사용자 | GET | `/api/hubs/{hubId}/stocks` | 허브의 재고 목록 |
| 로그인 사용자 | GET | `/api/hubs/{hubId}/products/{productId}/stocks` | 허브·상품으로 재고 상세 조회 |
| 로그인 사용자 | GET | `/api/products/{productId}/stocks` | 상품의 전체 허브 재고 비교 |

목록은 `page=0`, `size=20`이 기본이며 size는 1~100입니다. `createdAt DESC, id DESC`로 정렬하고 마지막 페이지 및 JPA offset 한계를 넘으면 200과 빈 목록을 반환합니다. page 자체가 int 범위를 넘거나 음수이면 400입니다.

생성 수량은 0~`Long.MAX_VALUE`의 JSON 정수만 허용합니다. 문자열 숫자, 소수(예: `1.9`, `-0.9`, `1.0`), 지수 표기, null 및 범위 초과는 400입니다. 역직렬화 시 정수로 자르거나 문자열을 숫자로 변환하지 않습니다.

재고가 없거나 논리 삭제됐으면 상세 조회는 404 `STOCK_NOT_FOUND`입니다. 없는 허브의 생성 요청은 404 `HUB_NOT_FOUND`, 같은 허브·상품의 중복 등록은 409 `STOCK_ALREADY_EXISTS`입니다. 기존 유니크 제약에 맞춰 논리 삭제된 재고의 키도 재등록하지 않으며, 재활성화는 별도 정책이 필요합니다.

`GET /api/stocks`와 `/api/stocks/{id}`는 제공하지 않습니다. 상품별 재고는 목록으로, 허브·상품 기준 상세는 단건으로 응답합니다. 생성 요청은 기존 재고를 증액·덮어쓰지 않습니다. 내부 재고 생성은 제거하며 주문 서비스는 조회·차감·복원을 담당합니다. 차감·복원은 기존 내부 API 계약을 따르고 이번 생성·조회 구현에는 포함하지 않습니다.

## 공통 담당자와 합의할 사항

Gateway와 공통 도메인 문서는 변경하지 않습니다. 공용 smoke는 hub OpenAPI에 `/api/products/{productId}/stocks`만 정확한 경로로 허용하며, 담당자 리뷰가 필요합니다. 이 문서 허용은 실제 Gateway 라우팅을 변경하지 않습니다.

| 담당 | 합의·연동할 내용 |
| --- | --- |
| Gateway·Company | `/api/products/{productId}/stocks`만 hub로 보내는 구체 라우트가 필요하다. 현재 `/api/products/**`는 company로 전달되므로 일반 상품 경로를 보존하면서 우선순위를 정한다. |
| CI·공통 | smoke의 정확한 경로 허용 추가를 리뷰한다. `/api/products` 전체나 그 외 상품·하위 경로는 hub 문서에 허용하지 않는다. |
| Company | 상품 내부 조회 API의 존재·삭제·연동 실패 응답과 공급업체·관리 허브 관계 검증 책임을 확정한다. |
| Users·Gateway·Orders | [Hub 인증·감사](security.md)의 JWT·서비스 토큰 계약으로 헤더 전달과 환경변수를 연결한다. 재고 최초 등록·추가는 MASTER이며 조회는 로그인 사용자다. |
| Orders | 최초 재고 등록은 외부 관리 API로 분리하고 내부 생성 계약을 제거하는 안을 SA에 반영한다. 내부 상세 조회는 허브·상품 ID로 호출한다. |
| Orders·Hub·공통 | StockOperation 독립 Aggregate 제거 여부와 중복 차감·복원 및 실패 복구의 대체 방식을 합의한 뒤 공통 도메인 문서와 SA를 갱신한다. |
| 공통·SA 담당 | 감사 작업자는 username 문자열, updated 필드는 생성 시에도 기록한다는 공통 규약을 SA와 일치시킨다. |

## 인증·감사 연동 경계

감사 어댑터는 `SecurityContext`의 검증된 username을 사용합니다. 외부 API는 Resource Server가 검증한 JWT의 `username` 클레임, 내부 API는 서비스 토큰 검증을 거친 `X-Actor-Username`을 사용합니다. 외부 JWT의 `sub`는 사용자 UUID이며 감사 username으로 대체하지 않습니다. 주체가 없거나 익명·미인증·유효하지 않은 이름이면 저장을 거부합니다. 임의 시스템 사용자로 대체하지 않습니다.

Hub의 로그인·MASTER 권한 검사와 내부 서비스 인증 수신은 구현되어 있습니다. 내부 URL은 Gateway 업무 라우트와 Swagger에서 제외합니다. 호출 서비스의 실제 헤더 전달·환경변수 주입과 Gateway 연동은 별도 확인이 필요합니다. 인증 테스트의 범위와 설정은 [Hub 인증·감사](security.md)를 따릅니다.

상품 존재·공급업체 관계를 어느 서비스에서 검증할지는 주문·업체 담당자와 확정해야 합니다. 현재 허브는 상품 UUID를 논리 참조하며 외부 상품 DB에 직접 접근하지 않습니다.
