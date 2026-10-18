# 실제 서비스 연동 테스트

Java 21·JUnit으로 실제 Users 로그인과 Gateway·Company·Hub·Orders·Delivery 연동을 확인합니다.
업무 서비스의 코드를 의존하거나 Spring 컨텍스트·클라이언트 대체 구현을 사용하지 않습니다.
각 서비스 내부의 Spring Boot·PostgreSQL 통합 테스트는 해당 모듈에 유지합니다.

## 실행

저장소 루트에서 실행합니다. Docker Compose와 Java 21이 필요합니다.
환경 준비는 [저장소 README](../README.md), 개발 계정·샘플은 [개발 데이터](../infra/dev-data/README.md)를 따릅니다.

```bash
bash scripts/init-env.sh
export COMPOSE_PROJECT_NAME=logistics-orders-recovery-local
export GATEWAY_PORT=18080 EUREKA_PORT=18761 ZIPKIN_PORT=19411 POSTGRES_PORT=15432
export GATEWAY_URL=http://127.0.0.1:18080
docker compose up -d --build --wait --wait-timeout 300
python3 scripts/smoke.py --gateway-url "$GATEWAY_URL" \
  --eureka-url http://127.0.0.1:18761 --zipkin-url http://127.0.0.1:19411
python3 scripts/check-dev-data.py --gateway-url "$GATEWAY_URL"
./gradlew :integration-tests:e2eTest
docker compose down -v --remove-orphans
```

`e2eTest`는 기존 전용 스택에 접속하며 컨테이너·네트워크·볼륨을 만들지 않습니다.
전체 검사는 `./gradlew :integration-tests:e2eTest`로 실행합니다. 세 단계는 순차 실행하며 같은 스택을 공유하고, 앞 단계가 실패하면 후속 단계를 실행하지 않습니다.
단계별로 확인할 때는 `:integration-tests:ordersFlowTest`, `:integration-tests:deliveryRecoveryTest`, `:integration-tests:stockRecoveryTest`를 차례로 실행하며, 각 통과를 확인한 뒤 다음 단계로 진행합니다.
프로젝트 이름은 `logistics-orders-recovery-`로 시작해야 하고, Gateway 주소는 같은 프로젝트의 로컬 공개 포트와 일치해야 합니다.
기본 개발 스택·데이터를 검증 목적으로 중단하거나 삭제하지 마세요. 실패해도 아래 복구 절차를 확인한 뒤 전용 스택을 종료하세요.

`:integration-tests:check`는 Docker 없이 검사 대상 보호·정리 동작을 확인하며 E2E를 실행하지 않습니다.
E2E는 캐시·이전 통과 결과를 재사용하지 않습니다. 각 결과는 `integration-tests/build/reports/tests/<작업 이름>/index.html`에 남습니다.
CI는 전용 스택을 한 번 시작해 인프라·개발 데이터·정상 주문·복구 순서로 검사하고, 성공·실패 모두 해당 스택을 종료합니다.

## 이전 Python 검사와의 대응

| 기존 검사 | Java 검사와 유지하는 범위 |
| --- | --- |
| `check-orders-flow.py` | `OrdersFlowTest`: 네 Delivery 내부 API 준비 상태, 실제 로그인·역할, 미인증 생성 거절, 같은 키 8개 동시 생성과 단일 주문·배송, 확정·요청사항, 재고 1회 차감, 소유권, 재요청, 반복 취소·삭제, 1회 복원·감사, 배송·경로 논리 삭제, 삭제된 키 재사용 거절 |
| `check-orders-recovery.py`의 Delivery 지연 | `OrdersRecoveryTest`: 실제 배송 INSERT 대기·호출 타임아웃, Orders 중지 뒤 외부 커밋, 재시작 전 미완료·CREATE 의도, 같은 주문 자동 확정, 재요청·배송·재고·감사 보존 |
| `check-orders-recovery.py`의 Hub 지연 | `OrdersRecoveryTest`: 실제 복원 INSERT 대기·호출 타임아웃, Orders 중지 뒤 외부 커밋, 재시작 전 미완료·CANCEL 의도, 같은 주문 자동 취소, 복원 기록 1건·재고·원래 요청자 보존 |

조회 전용 샘플 검사(`check-dev-data.py`), 인프라 점검(`smoke.py`), 환경 준비(`init-env.py`)는 기존 진입점을 유지합니다.

## 실패와 복구

DB 지연은 해당 서비스 계정의 전용 JDBC 트랜잭션으로 대상 테이블에 SHARE 잠금을 잡아 만듭니다.
실제 INSERT 대기를 관측하고 Orders를 중지한 뒤 잠금을 해제합니다. 재시작 후 GET 조회만으로 자동 복구를 확인한 다음 동일 키를 재요청합니다.
연결 종료 시 롤백하고 해당 장애 주입 세션이 사라졌는지 확인합니다. 준비·검사 실패에도 연결을 닫으며, 중지했던 Orders를 다시 시작합니다.
DB 잠금 획득과 유휴 트랜잭션에도 서버 시간 제한을 둡니다.

검증 주문은 취소·논리 삭제하고 재고는 시작 수량으로 돌아와야 합니다. 감사·멱등·복원 기록은 남습니다.
실패하면 테스트 출력의 주문 ID·멱등 키로 같은 주문과 복구 상태를 먼저 확인하세요. 주문 ID를 받지 못했을 때도 같은 키로 DB를 조회합니다.
새 키로 재주문하거나 재고를 추정 보정하지 않습니다. 비밀번호·JWT·서비스 토큰은 보고서에 출력하지 않습니다.

이 검사는 Orders 재시작과 두 지연 커밋을 다룹니다. Hub·Delivery 프로세스 재시작, 모든 네트워크 장애, 재고 차감 결과 불명의 안전한 복구와 배송 생성 실패 보상은 보장하지 않습니다.
