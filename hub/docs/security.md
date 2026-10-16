# Hub 인증·감사

Orders의 Resource Server 방식, 팀 JWT 규격과 공통 username 감사 규약을 따른다.
외부 사용자 인증과 내부 호출 서비스 인증은 서로 다른 보안 체인에서 처리한다.

## 외부 API

`Authorization: Bearer <JWT>`를 검증한다. `typ=JWT`, HS256, issuer `logistics-users`를
사용하고 아래 필수 클레임을 확인한다. 서명·만료·nbf도 검증한다.

| JWT 클레임 | 규격·용도 |
| --- | --- |
| `sub` | 사용자 UUID 문자열. 사용자 식별·소유권 확인에 사용 |
| `username` | 공백이 아닌 최대 100자 문자열. 공통 감사 작업자 기록에 사용 |
| `role` | `USER` 또는 `MASTER`. Spring Security의 `ROLE_USER`/`ROLE_MASTER`로 변환 |
| `is_delivery_staff` | 불리언 필수. Hub 재고 권한을 추가로 부여하지 않음 |
| `iat`, `exp` | 발급·만료 시각 필수. JWT NumericDate를 사용 |

`SecurityContext`의 외부 JWT 인증 이름은 `sub`의 UUID를 유지한다.
`HubAuditorAware`는 외부 JWT의 별도 `username` 클레임을 읽는다.
누락된 username을 UUID나 요청 헤더로 대체하지 않고 401로 거부한다.
`X-Actor-Username`, `X-Actor-Id`, 사용자 역할 헤더는 외부 인증에 사용하지 않는다.

Users가 발급하는 JWT에도 서명된 `username`이 포함되어야 한다. `sub`를 UUID로 바꾼 뒤
username을 생략한 토큰은 현재 공통 감사 계약을 만족하지 못한다. 이 Hub 변경에서
Users의 발급 구현을 수정하지 않으므로 실제 통합 전에 해당 클레임 추가를 맞춰야 한다.

| API | 권한 |
| --- | --- |
| 허브·기준 경로·재고 조회(`/api/products/{productId}/stocks` 포함) | USER 또는 MASTER |
| `POST /api/stocks` | MASTER |
| `POST /api/hubs/{hubId}/products/{productId}/stocks/add` | MASTER |

재고 등록·추가 메서드에서도 MASTER를 확인한다. 인증 실패는 401 `UNAUTHORIZED`,
권한 부족은 403 `FORBIDDEN`이며 모두 공통 `ApiErrorResponse`를 반환한다.
세션은 생성하지 않는다. 공개 GET은 health, OpenAPI, local 프로필의 인프라 ping이다.

## 내부 API

Orders Feign·Stub의 `X-Service-Token` + `X-Actor-Username` 방식으로 수신한다.
사용자 JWT만으로 내부 API를 호출할 수 없으며, 서비스 토큰으로 외부 API 권한을 얻을 수도 없다.

| 내부 API | 허용 서비스 |
| --- | --- |
| `GET /internal/hubs?name=` | Users |
| `GET /internal/hubs/{id}` | Users, Company |
| `GET /internal/hub-routes` | Delivery |
| `GET /internal/hubs/{hubId}/stocks/{productId}` | Orders |
| `POST /internal/stocks/deductions` | Orders |

회원가입 소속 조회 예외를 제외한 내부 호출은 `X-Actor-Username`이 필수다. 서비스 토큰을 먼저 검증한 뒤
username을 감사 주체로 설정하며, MASTER로 승격하지 않는다.
재고 차감 메서드에서도 `SERVICE_ORDERS`를 확인한다.

로그인 전 회원가입의 소속 조회인 Users의 `GET /internal/hubs?name=`만 서비스 인증만으로
허용한다. 이때 감사 주체를 만들지 않으며 쓰기에 사용할 수 없다. 그 외 내부 조회도 원 요청자를 전달한다.
전달된 username은 공백이 아니고 최대 100자여야 한다. 중복 인증 헤더는 거부한다.
알 수 없는 토큰·필수 감사 주체 누락은 401, 허용되지 않은 호출 서비스는 403이다.

내부 호출은 사용자 JWT를 필수로 요구하지 않는다. JWT가 없거나 만료되어도 서비스 인증과
원 요청자 계약이 유효하면 호출할 수 있다. 자동 복구의 호출자는 저장된 작업 정보의
원래 username을 전달하며, 복구 스케줄러가 임의 작업자로 바꾸지 않는다.

## 설정·연동

| Hub 환경변수 | 설정 |
| --- | --- |
| `JWT_SECRET` | Users·Orders와 같은 HS256 키, UTF-8 32바이트 이상 |
| `ORDERS_SERVICE_TOKEN` | Orders가 전송하는 서비스 토큰과 같은 값 |
| `USERS_SERVICE_TOKEN` | Users 내부 조회용 토큰 |
| `COMPANY_SERVICE_TOKEN` | Company 내부 조회용 토큰 |
| `DELIVERY_SERVICE_TOKEN` | Delivery 내부 조회용 토큰 |

서비스 토큰은 서로 다른 16자 이상의 비밀값을 사용한다. 미설정 토큰은 인증을 허용하지
않으며 JWT 키가 없으면 외부 인증도 401로 거부한다. 인증값은 문서·코드에 저장하지 않는다.

이 변경은 Hub 수신 구현이다. 실제 통합에는 각 호출 서비스의 헤더 전달과 동일한 토큰
설정, Gateway의 원본 Bearer 전달, Hub 실행 환경의 환경변수 주입이 필요하다.
Gateway·Compose·다른 서비스·공통 모듈은 이 변경에서 수정하지 않는다.
현재 사용자 활성 상태·변경된 권한의 즉시 반영은 Gateway·Users의 후속 연동 범위다.
서비스 토큰 소유자를 신뢰해 원래 요청자의 username을 위임받으므로 내부 통신의 접근
제한과 전송 보호도 배포 환경에서 적용해야 한다.

## 감사·검증

`HubAuditorAware`는 위 인증으로 설정된 username만 읽는다. 생성자와 생성 시각은 유지하고
변경 작업자의 username을 수정 감사에 기록한다. 주체가 없으면 저장을 거부하며 임의
시스템 주체로 대체하지 않는다. 인증 컨텍스트는 요청 종료 시 제거한다.

실제 서명 JWT·서비스 토큰을 사용하는 MVC 인증 테스트와 PostgreSQL 통합 테스트로
권한 거부 시 수량 불변, 생성·추가·차감의 감사 주체 보존을 확인한다.
기존 페이지·수량 경계값 테스트도 보안 필터를 활성화한 상태에서 수행한다.
실제 Gateway·Users·Orders를 연결한 전체 시스템 연동 검증은 별도다.

## 용어

| 항목 | 의미 |
| --- | --- |
| JWT `sub` | subject. 이 사용자 토큰의 주체인 사용자의 UUID |
| JWT `username` | 인증된 사용자의 username. 외부 요청의 감사 기록에 사용 |
| `X-Service-Token` | 호출 서비스를 인증하는 실행 환경의 비밀값. 수신 서비스는 이 값으로 호출자와 허용 API를 확인 |
| `X-Actor-Username` | 그 업무를 처음 요청한 사용자의 username. 서비스 인증을 통과한 내부 호출에서 감사 기록에 사용 |
