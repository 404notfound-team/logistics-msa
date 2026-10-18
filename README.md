# 404 Not Found · Logistics MSA

물류 MSA 팀 프로젝트입니다. 도메인 앱 5개, Gateway, Eureka,
PostgreSQL, Zipkin을 Docker Compose로 함께 실행합니다.

업무 API·JWT 인증/권한·개발용 샘플과 서비스 탐색·라우팅·분산 추적·API 문서를 제공합니다.
주문의 복구·보상 범위는 [주문 서비스](orders/README.md)를 따릅니다.
로컬 개발용 구성으로, 외부 서버 배포에 사용하지 않습니다.

## 구성

| 모듈 | 담당 범위 | DB / 스키마 |
| --- | --- | --- |
| `users` | 사용자·인증·배송 담당자 속성 | `users_db` / `users` |
| `company` | 업체·상품 | `company_db` / `company` |
| `hub` | 허브·허브 경로·재고 | `hub_db` / `hub` |
| `orders` | 주문·재고 차감·배송 요청 조정 | `orders_db` / `orders` |
| `delivery` | 배송·배송 경로 | `delivery_db` / `delivery` |
| `gateway` | 외부 API 진입점·서비스 라우팅 | 없음 |
| `eureka` | 서비스 등록·탐색 | 없음 |

각 앱은 독립적으로 실행되는 Gradle 하위 프로젝트입니다. `common:api`는 공통 응답·오류 계약,
`common:audit`는 JPA 감사 코드를 제공하는 일반 Java 라이브러리입니다.
업무 앱은 두 모듈, Gateway는 API 모듈만 사용하며 별도 공통 서버는 실행하지 않습니다.
사용 범위와 JSON·감사 필드는 [공통 API·감사 규칙](docs/common-contracts.md)을 따릅니다.

Java 21 · Spring Boot 3.5.16 · Spring Cloud 2025.0.3 · Gradle 8.14.6 · PostgreSQL 18 · Zipkin 3.6.1.
Boot 3.5에는 [Spring Cloud 2025.0 계열](https://spring.io/projects/spring-cloud/)을 사용합니다.

## 처음 실행

Docker와 Docker Compose v2가 필요합니다. Docker Desktop 메모리는 6 GB 이상을 권장합니다.
점검 스크립트는 Bash, OpenSSL, Python 3를 사용합니다. Docker 빌드에는 로컬 Java 설치가 필요하지 않습니다.

```bash
git clone https://github.com/404notfound-team/logistics-msa.git
cd logistics-msa
bash scripts/init-env.sh
docker compose up -d --build --wait --wait-timeout 300
python3 scripts/smoke.py
```

`init-env.sh`는 로컬 전용 DB·개발 계정 비밀번호와 인증 키를 생성합니다. Gateway와 업무 서비스 5개의 서비스 토큰을 모두 준비하며, 기존 DB 비밀번호와 지정한 키는 보존하고 없거나 비어 있는 키만 채웁니다.
직접 설정하려면 `.env.example`을 `.env`로 복사하고 빈 비밀번호를 모두 채웁니다.
포트가 이미 사용 중이면 `.env`의 포트를 변경하고 점검 스크립트에도
`--gateway-url`, `--eureka-url`, `--zipkin-url`을 전달하세요.

| 로컬 주소 | 용도 |
| --- | --- |
| http://localhost:8080/actuator/health | Gateway 상태 |
| http://localhost:8080/swagger-ui.html | 서비스별 Swagger UI |
| http://localhost:8761 | Eureka 대시보드 |
| http://localhost:9411 | Zipkin UI |
| http://localhost:8080/__infra/trace | Gateway → orders → hub 추적 점검 |

Eureka 등록·캐시 갱신에 시간이 걸릴 수 있어 점검 스크립트가 최대 3분간 기다립니다.
`/__infra/{users,company,hub,orders,delivery}`와 `/__infra/trace`는 `local` 프로필에서만
활성화되는 연결 점검 경로입니다. 업무 API의 구현 완료를 뜻하지 않습니다.
Zipkin에서 서비스 `orders`로 검색하면 세 앱의 span을 확인할 수 있습니다.

## Swagger와 OpenAPI

Compose가 준비되면 http://localhost:8080/swagger-ui.html 을 열고 상단의 `Select a definition`에서
서비스를 선택합니다. Gateway 포트를 바꿨다면 같은 포트로 접속합니다.

| 선택 항목 | 문서 | Gateway의 OpenAPI JSON 경로 |
| --- | --- | --- |
| `users` | 사용자 API | `/v3/api-docs/users` |
| `company` | 업체·상품 API | `/v3/api-docs/company` |
| `hub` | 허브·재고 API | `/v3/api-docs/hub` |
| `orders` | 주문 API | `/v3/api-docs/orders` |
| `delivery` | 배송 API | `/v3/api-docs/delivery` |

Gateway는 WebFlux용 Swagger UI를 제공하고, 각 업무 서비스는 MVC용 OpenAPI JSON을 생성합니다.
문서의 서버 주소는 `/`이므로 `Try it out`도 현재 접속한 Gateway의 호스트·포트를 사용합니다.
도메인 서비스의 포트를 호스트에 추가로 열거나 CORS를 허용할 필요가 없습니다.
Spring Boot 3.5와 호환되는 [springdoc 2.8 계열](https://springdoc.org/v2/#what-is-the-compatibility-matrix-of-springdoc-openapi-with-spring-boot)을 사용합니다.

문서와 UI는 기본 비활성이고, Compose가 사용하는 `local` 프로필에서 활성화합니다.
서비스별 공개 API 접두사만 문서에 포함하며 `/internal/**`·Actuator·인프라 점검 경로는 제외합니다.
응답 DTO의 필드명은 기존 Jackson 설정과 같은 `snake_case`로 표시합니다.
상단 로그인 폼에서 실제 Users 계정으로 로그인하면 서비스 선택을 바꿔도 인증된 API를 실행할 수 있습니다.
개발 계정·비밀번호·토큰 만료는 [개발용 샘플 데이터](infra/dev-data/README.md#바로-확인하기)를 참고하세요.

## 개발 DB 스키마

운영 배포를 하지 않는 개발 전용 프로젝트이므로 정식 마이그레이션 체계를 필수로 요구하지 않습니다.
users·company·hub·orders·delivery는 `local` 프로필에서 `ddl-auto: update`로
각자의 엔티티를 기준으로 자기 스키마의 테이블을 생성·갱신합니다. 각 담당자는 자기 서비스 모듈에서
엔티티와 테스트를 변경하고, 공유 DB 초기화 SQL에는 업무 테이블을 추가하지 않습니다.
DB 초기화 파일은 DB·스키마·계정 준비만 담당합니다. 이 방식은 공유 SQL 편집을 줄입니다.

각 앱은 자기 스키마에 테이블을 생성할 수 있고, 자신이 생성한 테이블의 구조를 변경할 수 있습니다.
관리자 소유의 기존 테이블은 자동 변경되지 않으므로 필요한 경우 해당 테이블의 소유권을 별도로 맞춥니다.
기본 프로필의 `validate` 설정은 유지하며, 로컬 실행에는 `local` 프로필을 사용합니다.

기존 로컬 DB 볼륨에는 초기화 파일 변경이 자동 적용되지 않습니다. 데이터를 보존하면서
자기 스키마의 생성 권한을 추가하려면 PostgreSQL을 실행한 상태에서 다음을 한 번 실행합니다.
이 명령은 테이블과 데이터를 삭제하거나 소유권을 변경하지 않습니다.

```bash
docker compose exec -T postgres bash -eu <<'SH'
for service in users company hub orders delivery; do
  psql --username "$POSTGRES_USER" --dbname "$service"_db -v ON_ERROR_STOP=1 \
    --set=schema="$service" --set=role="$service"_app <<'SQL'
SELECT format('GRANT USAGE, CREATE ON SCHEMA %I TO %I', :'schema', :'role') \gexec
SQL
done
SH
```

## 개발과 종료

`local` 프로필로 시작하면 허브·경로와 연결된 업체·상품·재고·주문·배송 샘플을 자동 적재합니다. Compose의 `dev-data` 작업은 Hub의 실제 거리·시간으로 배송 구간도 채운 뒤 종료합니다.
상태별 확인법, 시험 JWT, 재실행·검증 범위는 [개발용 샘플 데이터](infra/dev-data/README.md)를 참고하세요.

로컬 JDK 21을 설치하고 Gradle 실행 JDK도 21로 설정합니다(IntelliJ의 Gradle JVM 또는 `JAVA_HOME`).
Java 컴파일에 기본 타입 검사가 포함되므로 별도 typecheck 도구는 사용하지 않습니다.
`build`는 Spotless 포맷 검사, Checkstyle, Java 컴파일, JUnit 테스트와 패키징을 수행합니다.
각 모듈의 업무·권한·응답 계약·저장·동시성·복구 테스트와 공통 API·감사 테스트를 실행합니다.
DB 통합 테스트는 Docker에서 독립 PostgreSQL 18을 기동하므로 로컬에서도 Docker가 필요합니다.
업무 테스트는 각 모듈의 `src/test/java`에 추가합니다.
인프라 연동은 `scripts/smoke.py`, 실제 주문·장애 복구 흐름은 [Java 서비스 연동 테스트](integration-tests/README.md)로 검증합니다.

```bash
./gradlew spotlessApply            # Java 포맷 자동 수정
./gradlew --parallel --build-cache --continue build  # 코드 검사·테스트·패키징
./gradlew :orders:test             # 특정 서비스 테스트
node --test scripts/test_swagger_login.cjs  # Swagger 로그인 검사 (Node.js 18 이상)
docker compose logs -f orders
docker compose up -d --build orders
docker compose down
```

`down`은 DB 볼륨을 보존합니다. **로컬 데이터를 완전히 지우고 초기화할 때만**
`docker compose down -v`를 실행하세요. DB 초기화 스크립트는 빈 볼륨에서 한 번만 실행됩니다.
기존 볼륨의 비밀번호는 `.env` 수정만으로 바뀌지 않습니다.
Zipkin은 메모리 저장 방식이라 컨테이너 재시작 시 trace가 사라집니다.

기본 브랜치인 `develop`에서 개발 변경을 통합하고 `main`에서 릴리스를 관리합니다.
PR과 `develop`·`main` 변경 시 CI는 코드 검사·테스트(`quality`)와 앱 패키징·Compose 연동 검사(`smoke`)를
동시에 실행합니다. 두 작업이 모두 성공해야 필수 검사 `build-and-smoke`가 통과합니다.
Gradle은 모듈을 병렬로 처리하고 빌드 캐시를 사용합니다. 연동 검사는 미리 패키징한 JAR를
`compose.ci.yml`의 Docker 빌드 대상으로 사용하므로 컨테이너 안에서 Gradle을 다시 실행하지 않습니다.
앱 패키징 동안 PostgreSQL·Zipkin을 기동하고 공통 실행 이미지를 준비합니다.
CI에서는 Eureka 서버 응답 캐시와 클라이언트 목록을 5초마다 갱신해 서비스 탐색 대기를 줄입니다.
로컬의 기본 Compose 실행은 기존처럼 소스에서 앱을 빌드합니다.
포맷·코딩 규칙은 [협업 규칙](CONTRIBUTING.md#코드와-설정)을 따릅니다.

## 경계

- 호스트에 공개하는 포트는 Gateway·Eureka·Zipkin의 `127.0.0.1` 포트뿐입니다.
  도메인 앱과 PostgreSQL은 Compose 내부 네트워크에서 접근합니다.
- Gateway는 `/api/users/**`, `/api/companies/**`, `/api/products/**`, `/api/hubs/**`,
  `/api/hub-routes/**`, `/api/stocks/**`, `/api/orders/**`, `/api/deliveries/**`만 업무 서비스로 보냅니다.
  `/internal/**`을 외부 경로로 자동 공개하는 discovery locator는 사용하지 않습니다.
- PostgreSQL 컨테이너와 볼륨은 하나이며 DB·스키마·접속 계정은 서비스마다 다릅니다.
  앱 계정은 자기 DB 접속과 자기 스키마의 테이블 생성을 허용하며 자신이 생성한 테이블을 변경합니다.
  다른 DB 접속·public 스키마 생성·관리 권한은 없습니다.
  엔티티 변경과 기존 볼륨의 권한 반영은 [개발 DB 스키마](#개발-db-스키마)를 따릅니다.
- 다른 서비스 데이터는 Feign으로 조회합니다. 다른 DB 직접 SQL·물리 FK·업무 엔티티 공유를 하지 않습니다.
- Slack은 Orders가 외부 Webhook으로 호출하며 기본 비활성입니다. 설정과 실패 처리는 [주문 서비스](orders/README.md)를 따릅니다.

브랜치·커밋·PR 규칙은 [CONTRIBUTING.md](CONTRIBUTING.md)를 따릅니다.
