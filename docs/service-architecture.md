# 서비스 패키지 구조와 책임

서비스 `users`, `company`, `hub`, `orders`, `delivery`의 패키지 배치와 각 계층의 역할을 정합니다. Gateway와 Eureka는 진입점·서비스 탐색 역할에 맞게 구성합니다.

## 패키지 청사진

루트 패키지는 `com.notfound.logistics.<service>`입니다. 아래 구조에서 필요한 패키지만 만듭니다.

```text
com.notfound.logistics.<service>
├── presentation
│   ├── controller
│   ├── dto
│   └── exception
├── application
│   └── service
├── domain
│   ├── model
│   ├── exception
│   └── repository
└── infrastructure
    ├── client
    ├── config
    └── audit
```

여러 기능 영역을 소유하면 계층 안에서 기능별 하위 패키지를 나눕니다. 패키지와 타입은 실제 필요한 만큼만 만듭니다.

## 계층별 책임

| 계층 | 두는 코드 | 책임 |
| --- | --- | --- |
| `presentation` | Controller, 요청·응답 DTO, HTTP 예외 처리기 | 요청 형식 검증, Service 호출, HTTP 응답 구성 |
| `application` | Service | 회원가입·주문 생성 같은 기능의 처리 순서, 권한 확인, 트랜잭션과 외부 호출 조율 |
| `domain` | 엔티티·값 객체, 상태 규칙·예외, Repository | 데이터 상태와 변경 조건, 데이터 조회·저장 정의 |
| `infrastructure` | Feign Client, 설정, 감사 연동, 필요한 저장소 구현 | 외부 서비스 호출과 실행 환경·DB 연결 설정 |

요청의 필수값·형식은 `presentation`에서 검증합니다. 주문 취소 가능 상태나 재고 수량 같은 데이터의 변경 조건은 `domain`에서 판단합니다. `application`은 인증 주체·권한을 확인하고 필요한 조회·저장·외부 호출을 진행합니다.

## 호출과 데이터 전달

- Controller는 Service를 호출하고, Service는 엔티티·Repository·외부 조회 결과를 사용해 기능을 처리합니다.
- Repository는 `domain.repository`에 둡니다. `JpaRepository` 등 Spring Data 인터페이스를 상속할 수 있으며, Service에서 생성자로 주입받아 사용합니다.
- Feign Client와 외부 API의 전송 DTO는 `infrastructure.client`에 둡니다. Service에서 Feign Client를 생성자로 주입받아 직접 호출할 수 있습니다.
- 엔티티는 JPA로 매핑하고 공통 `AuditEntity`를 상속합니다. 엔티티는 자신의 데이터와 상태 규칙을 처리하며 HTTP 요청·응답이나 다른 서비스 호출을 담당하지 않습니다.
- 업무 예외와 오류는 `domain.exception`, HTTP 상태·`ApiErrorCode` 구현·응답 변환은 `presentation.exception`에 둡니다.
- `AuditorAware`와 인증 주체를 읽는 코드는 `infrastructure`에 둡니다. 요청 헤더를 직접 신뢰하거나 임의의 감사 주체로 대체하지 않습니다.
- 요청·응답 DTO는 `presentation.dto`에 둡니다. Controller와 Service가 같은 데이터를 사용하면 DTO를 함께 사용할 수 있습니다. 데이터 구조나 규칙을 구분할 필요가 있을 때 별도 타입을 추가하며, 엔티티를 HTTP 응답으로 직접 반환하지 않습니다.

Spring Data Repository나 Feign Client를 사용하는 것만으로 별도 인터페이스·구현 클래스를 추가할 필요는 없습니다. 서로 다른 구현을 지원하거나 반복되는 처리를 묶는 등 구체적인 필요가 있을 때 추가합니다.

## 공통 모듈과 테스트

`common:api`·`common:audit`의 제공 범위와 감사·삭제 동작은 [공통 API·감사 규칙](common-contracts.md)을 따릅니다. 업무별 엔티티·DTO·예외·인증 구현은 담당 서비스에 둡니다.

테스트는 `src/test/java`에서 대상 코드와 대응하는 패키지에 둡니다. HTTP 오류 변환은 응답 계약으로, 감사·저장소 연결은 통합 테스트로 확인합니다.
