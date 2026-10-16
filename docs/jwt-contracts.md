## JWT 헤더

```json
{
  "typ": "JWT",
  "alg": "HS256"
}
```

`alg`는 HMAC-SHA256 서명 알고리즘입니다. **서명**에 사용되며, 페이로드를 암호화하지는 않습니다.

## JWT 페이로드

```json
{
  "iss": "logistics-users",
  "sub": "4c7c4c0f-94fb-40d2-b467-5ccaecc9de3e",
  "username": "user01",
  "is_delivery_staff": false,
  "role": "USER",
  "exp": 1792122535,
  "iat": 1792121635
}
```

| 필드 | 규칙 |
| --- | --- |
| `iss` | 발급자를 나타냅니다. Users 서비스가 발급하며 값은 `logistics-users`입니다. |
| `sub` | 토큰의 주체인 사용자 ID를 UUID 문자열로 담습니다. |
| `username` | 사용자 username입니다. 감사 필드의 작업자와 내부 호출의 `X-Actor-Username`에 사용합니다. |
| `is_delivery_staff` | 사용자의 배송 담당 여부를 불리언으로 담습니다. |
| `role` | 사용자 역할을 문자열로 담습니다. 허용 값은 `MASTER`, `USER`입니다. |
| `iat` | 토큰 발급 시각입니다. Unix 초 단위로 기록합니다. |
| `exp` | 토큰 만료 시각입니다. Unix 초 단위로 기록하며, 만료된 토큰은 인증에 사용할 수 없습니다. |


토큰은 `Authorization: Bearer <JWT>` 형식으로 요청에 전달합니다. 서명 검증과 만료 여부 확인에 실패하면 인증을 거부합니다. JWT에는 비밀번호나 비밀번호 해시를 넣지 않습니다.

### 사용예
```java
UUID userId = UUID.fromString(jwt.getSubject()); // sub
String username = jwt.getClaimAsString("username");
String role = jwt.getClaimAsString("role");
Boolean deliveryStaff = jwt.getClaimAsBoolean("is_delivery_staff");
```

## 서비스의 Resource Server 설정

각 서비스는 OAuth2 Resource Server로 JWT를 검증하고 `SecurityContext`를 구성합니다. 인증 주체 이름은 `username`, 권한은 `role` 클레임으로 맞춥니다. 그러면 `Authentication#getName()`이 username을 반환하므로 감사 작업자(`AuditorAware`)와 `X-Actor-Username`에 그대로 사용할 수 있습니다.

```java
JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
authorities.setAuthoritiesClaimName("role");
authorities.setAuthorityPrefix("ROLE_"); // ROLE_MASTER, ROLE_USER
JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
converter.setPrincipalClaimName("username");
converter.setJwtGrantedAuthoritiesConverter(authorities);

http.oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)));
```

사용자 ID가 필요하면 `JwtAuthenticationToken#getToken().getSubject()`를 사용합니다. 기준 구현은 Users의 `UserSecurityConfiguration`입니다.

## Gateway 인증 흐름

Gateway는 외부 요청을 하위 서비스로 라우팅하기 전에 아래 순서로 처리합니다.

1. 외부에서 넣은 서비스 인증·사용자 식별 헤더를 제거합니다. 대상은 `X-Service-Token`, `X-Actor-Username`, `X-User-`로 시작하는 헤더이며 대소문자를 구분하지 않습니다. 공개 엔드포인트에도 적용합니다.
2. `/api/**` 중 공개 엔드포인트(`POST /api/users/signup`, `POST /api/users/signin`)를 제외한 요청은 JWT 서명·만료·발급자(`iss`)와 `sub`·`username`·`role`·`is_delivery_staff` 클레임을 검증합니다.
3. Users 내부 API `GET /internal/users/{id}/authorization`을 `X-Service-Token`(Gateway 서비스 인증)으로 호출해 현재 사용자의 활성 여부, username, 역할, 배송 담당 여부가 JWT와 같은지 확인합니다. 이 호출에는 사용자 JWT를 보내지 않습니다. 탈퇴했거나 JWT 발급 이후 권한이 바뀐 사용자의 기존 토큰은 거절합니다.
4. 검증을 통과한 요청의 `Authorization: Bearer <JWT>`는 변경하지 않고 대상 서비스로 전달합니다. 각 서비스는 OAuth2 Resource Server로 이 JWT를 다시 검증하고 업무 권한을 검사합니다.

| 상황 | 응답 |
| --- | --- |
| 토큰 없음, 형식 오류, 서명·만료·발급자 검증 실패, 필수 클레임 누락 | 401 `UNAUTHORIZED` |
| 탈퇴·삭제된 사용자, JWT의 username·역할·배송 담당 여부가 현재 값과 다름 | 401 `UNAUTHORIZED` |
| `MASTER` 전용 경로를 다른 역할이 호출 | 403 `FORBIDDEN` |
| Users 서비스 조회 실패·지연(2초 초과), Gateway 서비스 인증 거절 | 503 `SERVICE_UNAVAILABLE` |

Gateway의 역할 검사는 경로 단위의 최소 검사입니다. 소유자·담당자 같은 업무 권한은 각 서비스가 검사합니다.
