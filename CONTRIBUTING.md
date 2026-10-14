# 협업 규칙

## 브랜치

- `develop`: 기본 브랜치이자 개발 통합 브랜치. 일반 작업 브랜치는 최신 `develop`에서 만들고 PR도 `develop`으로 보냅니다.
- `main`: 릴리스 기준 브랜치. 검증된 `develop`의 변경을 PR로 반영합니다.
- `develop`과 `main`에는 직접 push·force push하지 않습니다.
- 작업 브랜치: `<type>/<scope>/<issue-number>-<short-description>`.
  예: `feat/orders/12-create-order`, `fix/hub/23-stock-lock`.
- 저장소를 처음 만드는 작업만 `chore/infra/bootstrap`을 사용하고 첫 커밋 후 `main`으로 이름을 바꿉니다.
- scope: `users`, `company`, `hub`, `orders`, `delivery`, `gateway`, `eureka`, `common`, `infra`, `project`.
- 한 브랜치와 PR에는 한 가지 목적을 담습니다. 머지 후 작업 브랜치를 삭제합니다.
- 선행 PR에 의존하는 작업만 해당 작업 브랜치를 임시 base로 사용할 수 있습니다.
  선행 PR 병합 후 최신 `develop` 위로 rebase하고 PR의 base도 `develop`으로 바꾼 뒤 diff·CI·승인을 다시 확인합니다.

## 커밋과 PR 제목

```text
<type>(<scope>): <변경 내용>

feat(orders): 주문 생성 API 추가
fix(hub): 동시 재고 차감 중복 반영 방지
chore(infra): 로컬 실행 환경 초기화
```

type은 `feat`, `fix`, `refactor`, `docs`, `test`, `style`, `build`, `ci`, `chore`를 사용합니다.
설명은 한국어로 간결하게 작성합니다. 본문이 필요하면 변경 방식·이유·검증을 적습니다.
관계없는 포맷 수정이나 생성물을 함께 커밋하지 않습니다.
커밋은 의미 있는 변경 단위로 남기고 임시 커밋은 리뷰 요청 전에 `amend` 또는 interactive rebase로 정리합니다.
Rebase and merge로 각 커밋이 보존되므로 커밋 목록과 메시지도 검토합니다.
리뷰 후 커밋이 바뀌면 최종 커밋 기준으로 CI와 승인을 다시 받습니다.
공유한 작업 브랜치의 이력을 다시 써야 한다면 공동 작업자와 먼저 맞추고 `--force-with-lease`를 사용합니다.

## PR과 리뷰

1. 이슈에 범위와 완료 조건을 적고 작업 브랜치를 만듭니다.
2. PR에 변경 이유, 주요 변경, 검증 결과, 연결 이슈를 적습니다.
3. 작성자 이외 팀원 1명 이상의 승인을 받습니다. API·DB·공통 모듈 변경은 영향을 받는 담당자에게 리뷰를 요청합니다.
4. 최신 base와 최종 커밋의 `build-and-smoke` 검사 통과, 승인과 대화 해결을 확인하고 **Rebase and merge**합니다.
   작업 브랜치의 base 동기화도 rebase합니다.

`develop`과 `main`은 관리자에게도 PR 승인 1개, 마지막 push 이후 작성자 외 승인,
최신 base 기준 CI 통과, 대화 해결과 선형 이력을 요구하며 강제 push·브랜치 삭제를 막습니다.

## 코드와 설정

- Java 포맷은 Spotless의 Google Java Format(기본 2칸 들여쓰기)을 사용합니다.
  자동 정렬은 `./gradlew spotlessApply`, 전체 검사는 `./gradlew --continue clean build`로 실행합니다.
- Checkstyle은 이름 규칙, 와일드카드 import, 빈 문장, equals/hashCode 쌍과 JDK 내부 API import를 검사합니다.
  세부 규칙은 `config/checkstyle/checkstyle.xml`을 따릅니다. 포맷과 검사 도구 버전은 루트 `build.gradle`에서 관리합니다.
- CI의 `build-and-smoke`는 포맷·Checkstyle·Java 컴파일·JUnit 테스트·패키징·서비스 연동 검사를 모두 통과해야 성공합니다.
  CI가 코드를 자동 수정하지는 않습니다. 검사 보고서는 실행 화면의 `verification-reports`에서 7일간 내려받을 수 있습니다.
- 도메인 로직과 엔티티는 담당 서비스가 소유합니다. 서비스 간 계약 변경은 호출자와 함께 검토합니다.
- `.env`, 비밀번호, JWT 키, Webhook URL은 커밋하지 않습니다. 변수 이름만 `.env.example`에 공유합니다.
- 새 API·테이블은 SA/API 명세와 함께 갱신하고 해당 동작을 검증하는 테스트를 추가합니다.
- 공개 API는 서비스의 `springdoc.paths-to-match`와 Gateway의 업무 라우트에 맞는 경로를 사용합니다.
  컨트롤러에는 `@Tag`·`@Operation`, DTO에는 필요한 `@Schema` 설명을 추가하고 Swagger에서 확인합니다.
  새 경로 접두사를 도입하면 라우트·문서 필터·`scripts/smoke.py`를 함께 갱신합니다.
  `/internal/**`를 Swagger에 노출하거나 문서의 서버 주소를 개별 서비스 주소로 바꾸지 않습니다.
  JWT 구현 시에는 실제 인증 방식에 맞는 OpenAPI 보안 정의와 인증 테스트도 함께 추가합니다.
- 인프라 변경은 `docker compose` 시작과 `scripts/smoke.py`까지 확인합니다.
- 인증 기능을 구현하기 전에는 이 구성을 외부에 배포하지 않습니다.
