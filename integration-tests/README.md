# 실제 서비스 연동 테스트

별도 터미널에서 저장소 루트를 작업 디렉터리로 사용합니다. 전용 Compose 환경에서 실행하며 일반 모듈 검사와 구분합니다.

## 실행

Java 21·JUnit으로 실제 Users 로그인과 Gateway·Company·Hub·Orders·Delivery를 검사합니다.
업무 서비스 코드·Spring 컨텍스트·클라이언트 대체 구현에 의존하지 않습니다. Docker Compose와 JDK 21이 필요합니다.

```bash
bash scripts/init-env.sh
export COMPOSE_PROJECT_NAME=logistics-orders-recovery-local
export GATEWAY_PORT=18080 EUREKA_PORT=18761 ZIPKIN_PORT=19411 POSTGRES_PORT=15432
export GATEWAY_URL=http://127.0.0.1:18080
docker compose up -d --build --wait --wait-timeout 300
./gradlew :integration-tests:e2eTest
python3 scripts/smoke.py --gateway-url "$GATEWAY_URL" \
  --eureka-url http://127.0.0.1:18761 --zipkin-url http://127.0.0.1:19411
python3 scripts/check-dev-data.py --gateway-url "$GATEWAY_URL"
```

기본 개발 스택·볼륨은 검증 목적으로 중단·삭제하지 않습니다. 검사에 실패하면 [실패와 복구](#실패와-복구)를 확인한 뒤 전용 스택을 종료합니다.
프로젝트 이름은 `logistics-orders-recovery-`로 시작해야 하고 Gateway 주소는 같은 프로젝트의 로컬 공개 포트와 일치해야 합니다.
검사는 기존 스택에 접속하며 컨테이너·네트워크·볼륨을 만들지 않습니다.

| 순서·Gradle 작업 (`:integration-tests:` 이하) | 검증 범위 |
| --- | --- |
| 1. `coldStartTest` | 첫 로그인·첫 주문·후속 주문 시간, 두 주문의 201·CONFIRMED 즉시 확정과 같은 키 재요청의 중복 방지 |
| 2. `ordersFlowTest` | 내부 API 준비·로그인·미인증 거절, 같은 키 8개 동시 생성, 단일 주문·배송·재고 차감, 소유권·멱등, 반복 취소·삭제, 복원 1회·감사·배송/구간 논리 삭제·삭제된 키 재사용 거절 |
| 3. `deliveryRecoveryTest` | 배송 INSERT 지연·타임아웃 → Orders 중지 → 외부 커밋 → 재시작 후 같은 주문 자동 확정·CREATE 의도·중복 방지·재고/감사 보존 |
| 4. `stockRecoveryTest` | 복원 INSERT 지연·타임아웃 → Orders 중지 → 외부 커밋 → 재시작 후 같은 주문 자동 취소·CANCEL 의도·복원 1건·재고/원래 요청자 보존 |

`e2eTest`는 위 순서로 같은 스택을 공유하며 앞 단계가 실패하면 후속 단계를 실행하지 않습니다. 단계별 실행도 앞 단계 통과 후 진행합니다.
`coldStartTest`는 새 JVM을 시작한 직후, 다른 업무 API 검사보다 먼저 실행합니다.
캐시·이전 통과 결과를 재사용하지 않으며 보고서는 `integration-tests/build/reports/tests/<작업 이름>/index.html`에 남습니다.
CI는 첫 요청 검사 후 인프라·개발 데이터·정상 주문·복구를 검사하고 전용 스택을 종료합니다.

## 첫 요청과 타임아웃

컨테이너는 Spring Boot JAR를 추출한 뒤 실행해 중첩 JAR 읽기 비용을 줄입니다.
Gateway의 업무 서비스, Orders의 Company·Hub·Delivery, Delivery의 Hub용 LoadBalancer 컨텍스트는 시작할 때 초기화하며 업무 API를 미리 호출하지 않습니다.
Orders 업무 클라이언트의 연결·응답 제한은 `orders/src/main/resources/application.yml`의 `orders.clients`에서 관리합니다. 기본값은 각각 1초·2초이며 Java 코드에서 덮어쓰지 않습니다.
로컬 인프라 탐침 `infraHubProbe`는 별도의 기존 2초·5초 제한을 유지합니다. 타임아웃 후 외부 커밋·재시작 복구 검사는 아래와 같이 계속 수행합니다.
로컬 Compose는 Eureka의 지연된 읽기 전용 응답 캐시를 끄고 등록 목록을 5초마다 갱신합니다. CI도 같은 설정을 사용해 기본 실행에서만 서비스 발견이 늦는 차이를 없앱니다.

## 실패와 복구

DB 지연은 서비스 계정의 JDBC 트랜잭션으로 SHARE 잠금을 잡아 만듭니다. 실제 INSERT 대기를 관측한 뒤 Orders를 중지하고 잠금을 해제합니다.
재시작 후 GET 조회로 자동 복구를 확인하고 같은 키를 재요청합니다. 잠금 획득·유휴 트랜잭션에는 서버 시간 제한을 둡니다.
실패해도 연결 종료·롤백·장애 주입 세션 해제를 확인하고 중지한 Orders를 다시 시작합니다.

검증 주문은 취소·논리 삭제하고 재고를 시작 수량으로 되돌리지만 감사·멱등·복원 기록은 남습니다.
실패하면 출력된 주문 ID·키로 같은 주문을 확인합니다. ID를 받지 못했으면 같은 키로 DB를 조회하며 새 키 재주문·추정 재고 보정은 하지 않습니다.
비밀번호·JWT·서비스 토큰은 보고서에 출력하지 않습니다. 실패 상태 확인 후 전용 스택을 종료합니다.

Orders 재시작·두 지연 커밋을 검증하며 Hub·Delivery 프로세스 재시작·모든 네트워크 장애·차감 결과 불명 복구·배송 생성 실패 보상은 포괄하지 않습니다.

## 전용 스택 종료

검사 성공 후 또는 실패 상태 확인을 마친 뒤, **이 전용 프로젝트의 데이터만 폐기할 때** 실행합니다.

```bash
docker compose down -v --remove-orphans
```
