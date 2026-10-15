# 서비스 패키지 구조와 책임

업무 서비스 `users`, `company`, `hub`, `orders`, `delivery`의 도메인 주도 설계(DDD) 기준입니다. Gateway와 Eureka는 진입점·서비스 탐색 역할에 맞게 구성합니다.

## 패키지 청사진

루트 패키지는 `com.notfound.logistics.<service>`입니다. 아래 구조에서 필요한 패키지만 만듭니다.

```text
com.notfound.logistics.<service>
├── presentation
│   ├── controller
│   ├── dto
│   └── exception
├── application
│   ├── service
│   └── port
├── domain
│   ├── model
│   ├── exception
│   └── repository
└── infrastructure
    ├── persistence
    ├── client
    └── audit
```

여러 업무 영역을 소유하면 계층 안에서 업무별 하위 패키지를 나눕니다. 사용하지 않는 인터페이스·기본 클래스는 만들지 않습니다.

## 계층별 책임

| 계층 | 두는 코드 | 책임 |
| --- | --- | --- |
| `presentation` | Controller, HTTP DTO, 예외 처리기 | 입력 형식 검증, 애플리케이션 호출, HTTP 응답 구성 |
| `application` | 유스케이스 서비스, 외부 연동 인터페이스 | 트랜잭션 경계, 도메인·외부 작업 조율 |
| `domain` | 엔티티·값 객체, 업무 규칙·예외, 저장소 인터페이스 | 상태 전이와 업무 불변식 보장 |
| `infrastructure` | 저장소 구현, Feign Client, 인증·감사 어댑터와 설정 | 기술 연결, 안쪽 계층의 인터페이스 구현 |

필수값·타입은 `presentation`에서 검증하고, 취소 가능 상태 같은 업무 조건은 `domain`에서 판단합니다. `application`은 인증 주체·권한 확인과 업무 호출을 조율합니다.

## 의존 방향과 경계

- `presentation`은 `application`을 호출하고, `application`은 `domain`을 사용합니다.
- 저장소 인터페이스는 `domain.repository`, 외부 연동 인터페이스는 `application.port`에 둡니다. `infrastructure`가 구현하고 애플리케이션에 생성자로 주입합니다.
- `domain`은 HTTP 응답·상태 코드, Spring Data 저장소, Feign, SecurityContext와 다른 서비스의 구현을 참조하지 않습니다. `application`은 구체적인 저장소·Feign 구현 대신 인터페이스를 사용합니다.
- 업무 엔티티는 JPA로 매핑하고 공통 `AuditEntity`를 상속합니다.
- 업무 예외와 오류는 `domain.exception`, HTTP 상태·`ApiErrorCode` 구현·응답 변환은 `presentation.exception`에 둡니다.
- `AuditorAware`와 검증된 인증 주체를 읽는 어댑터는 `infrastructure`에 둡니다. 요청 헤더를 직접 신뢰하거나 임의의 감사 주체로 대체하지 않습니다.
- HTTP DTO는 `presentation`, 외부 API의 전송 DTO는 `infrastructure.client`에 둡니다. 경계를 넘을 때 유스케이스 입력·결과나 업무 타입으로 변환하고, 엔티티를 HTTP 응답으로 반환하지 않습니다.

## 공통 모듈과 테스트

`common:api`·`common:audit`의 제공 범위와 감사·삭제 동작은 [공통 API·감사 규칙](common-contracts.md)을 따릅니다. 업무별 엔티티·DTO·예외·인증 구현은 담당 서비스에 둡니다.

테스트는 `src/test/java`에서 대상 코드와 대응하는 패키지에 둡니다. HTTP 오류 변환은 응답 계약으로, 감사·저장소 연결은 통합 테스트로 확인합니다.
