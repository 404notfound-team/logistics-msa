# 서비스 패키지 구조와 책임

`users`, `company`, `hub`, `orders`, `delivery`의 루트 패키지는 `com.notfound.logistics.<service>`입니다. Gateway와 Eureka는 각 역할에 맞게 구성합니다.

## 패키지와 역할

| 계층 | 하위 패키지 | 책임 |
| --- | --- | --- |
| `presentation` | `controller`·`dto`·`exception` | 요청 형식 검증, Service 호출, HTTP 응답 구성 |
| `application` | `service` | 권한 확인, 처리 순서·트랜잭션·외부 호출 조율 |
| `domain` | `model`·`repository`·`exception` | 엔티티·값 객체의 상태 규칙, 데이터 조회·저장 정의 |
| `infrastructure` | `client`·`config`·`audit` | 외부 API·인증·감사·DB 연결과 필요한 저장소 구현 |

여러 기능을 소유하면 계층 안에서 기능별로 나눕니다. 패키지와 타입은 필요한 만큼만 만듭니다.

## 구현 기준

- `domain.repository`의 Repository는 `JpaRepository` 등 Spring Data 인터페이스를 상속할 수 있습니다.
- Service는 Repository와 `infrastructure.client`의 Feign Client를 생성자로 주입받아 직접 사용할 수 있습니다.
- Controller와 Service가 같은 데이터를 쓰면 `presentation.dto`를 공유할 수 있습니다. 외부 API의 전송 DTO는 `infrastructure.client`에 둡니다.
- 추가 인터페이스·어댑터나 별도 DTO는 구현이나 데이터 구조를 구분할 필요가 있을 때 만듭니다.
- 엔티티는 JPA로 매핑하고 공통 `AuditEntity`를 상속합니다. 엔티티에 HTTP 처리·외부 호출을 넣거나 엔티티를 HTTP 응답으로 직접 반환하지 않습니다.
- 업무 예외와 오류는 `domain.exception`, HTTP 상태·`ApiErrorCode` 구현·응답 변환은 `presentation.exception`에 둡니다.
- `AuditorAware`와 인증 주체 조회 코드는 `infrastructure`에 둡니다. 요청 헤더를 직접 신뢰하거나 임의의 감사 주체로 대체하지 않습니다.

## 공통 규칙과 테스트

공통 모듈의 사용 범위와 응답·감사·삭제 규칙은 [공통 API·감사 규칙](common-contracts.md)을 따릅니다. 업무별 엔티티·DTO·예외·인증 구현은 담당 서비스에 둡니다.

테스트는 `src/test/java`의 대상 코드에 대응하는 패키지에 둡니다. HTTP 오류 변환은 응답 계약으로, 감사·저장소 연결은 통합 테스트로 확인합니다.
