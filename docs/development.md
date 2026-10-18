# 로컬 개발과 검증

명령은 저장소 루트에서 실행합니다. 처음 기동은 [빠른 시작](../README.md#빠른-시작)을 따릅니다.

## 환경 준비

Docker·Compose v2, Bash·OpenSSL·Python 3가 필요합니다. Docker 빌드는 로컬 JDK 없이 가능하며 로컬 Gradle은 JDK 21이 필요합니다.

`init-env.sh`는 새 `.env`에 DB 비밀번호·JWT 키·서비스 토큰·개발 비밀번호를 생성합니다.
기존 파일은 DB 비밀번호를 보존하고 빈 인증값만 보충하므로, 비어 있는 DB 비밀번호는 직접 채웁니다.
갱신 후 앱을 재시작합니다. `.env` 수정만으로 기존 DB·개발 계정의 비밀번호가 바뀌지는 않습니다.
Eureka 등록 대기를 포함해 smoke가 최대 3분간 기다릴 수 있습니다.

## 로컬 주소와 접근

| 기본 로컬 주소 | 확인할 내용 |
| --- | --- |
| http://localhost:8080/swagger-ui.html | 서비스 선택 → 공개 API 계약 확인 |
| http://localhost:8080/v3/api-docs/orders | OpenAPI JSON. orders를 users/company/hub/delivery로 교체 가능 |
| http://localhost:8080/actuator/health | Gateway 상태 |
| http://localhost:8080/__infra/trace | Gateway → Orders → Hub 추적. local 전용 연결 점검 |
| http://localhost:8761 / http://localhost:9411 | Eureka / Zipkin UI. Zipkin에서 orders 검색 |

Swagger는 `local`에서만 활성화하며 공개 업무 API만 표시합니다. Try it out은 현재 Gateway 주소를 사용합니다.
Swagger 로그인·서비스 간 인증 공유는 [샘플 조회](../infra/dev-data/README.md#조회)를 따릅니다.
호스트 공개 포트는 Gateway·Eureka·Zipkin·PostgreSQL의 `127.0.0.1` 바인딩입니다. 도메인 앱은 Compose 내부에서만 접근합니다.
포트를 바꾸면 스크립트의 `--gateway-url` 및 smoke의 `--eureka-url`·`--zipkin-url`도 맞춥니다.

## 검증

| 검증 | 명령·범위 |
| --- | --- |
| 정적 검사·테스트·패키징 | `./gradlew --parallel --build-cache --continue build` — Spotless·Checkstyle·컴파일·JUnit·JAR |
| 서비스별 검사 | `./gradlew :orders:check :orders:bootJar` — HTTP·독립 PostgreSQL, 클라이언트 장애는 테스트 내부 주입 |
| 연결·인증·추적 | `python3 scripts/smoke.py` |
| 조회 샘플·권한·구간 | `python3 scripts/check-dev-data.py` — 초기값 기준, 데이터 변경 없음 |
| 실제 주문·복구 연동 | `./gradlew :integration-tests:e2eTest` — [전용 Compose 환경](../integration-tests/README.md#실행)에서 실행 |

Orders 모듈 테스트는 실제 HTTP·독립 PostgreSQL을 사용하며 외부 클라이언트와 Slack은 테스트 안에서 대체합니다.
`:integration-tests:check`는 Docker 없이 검사 대상 보호·정리 동작을 확인하고, 실제 서비스 연동 검사는 별도로 실행합니다.

## 개발 환경 관리

```bash
./gradlew spotlessApply                   # Java 포맷 수정
docker compose logs -f orders
docker compose up -d --build orders        # 특정 앱 갱신
docker compose down                       # DB 볼륨 보존
```

Zipkin은 메모리 저장이므로 재시작하면 trace가 사라집니다.
CI는 `quality`와 `smoke`가 모두 통과해야 `build-and-smoke`가 성공합니다. smoke는 인프라 → 샘플 → `e2eTest`를 검사하고 성공·실패 모두 전용 스택을 종료합니다([CI](../.github/workflows/ci.yml)).

## 기존 볼륨의 스키마 생성 권한 보완

초기화 SQL은 빈 볼륨에서만 실행됩니다. 데이터·테이블 소유권을 보존하고 생성 권한만 추가하려면 PostgreSQL 실행 중 한 번 실행합니다.

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

복원 기록을 지원하는 Hub를 먼저 갱신한 뒤 Orders를 갱신합니다. 비멱등 복원 API를 제공하는 구형 Hub에 새 Orders를 연결하지 않습니다.
