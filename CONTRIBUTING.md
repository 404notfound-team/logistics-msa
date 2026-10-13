# 협업 규칙

## 브랜치

- `main`: 빌드와 연동 점검을 통과한 통합 브랜치. 초기 저장소 등록 이후 직접 push·force push 금지.
- 작업 브랜치: `<type>/<scope>/<issue-number>-<short-description>`.
  예: `feat/orders/12-create-order`, `fix/hub/23-stock-lock`.
- 저장소를 처음 만드는 작업만 `chore/infra/bootstrap`을 사용하고 첫 커밋 후 `main`으로 이름을 바꿉니다.
- scope: `users`, `company`, `hub`, `orders`, `delivery`, `gateway`, `eureka`, `common`, `infra`, `project`.
- 한 브랜치와 PR에는 한 가지 목적을 담습니다. 머지 후 작업 브랜치를 삭제합니다.

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

## PR과 리뷰

1. 이슈에 범위와 완료 조건을 적고 작업 브랜치를 만듭니다.
2. PR에 변경 이유, 주요 변경, 검증 결과, 연결 이슈를 적습니다.
3. 작성자 이외 팀원 1명 이상의 승인을 받습니다. API·DB·공통 모듈 변경은 영향을 받는 담당자에게 리뷰를 요청합니다.
4. `build-and-smoke` 검사 통과와 대화 해결을 확인하고 Squash merge합니다.
   Squash 커밋 제목도 위 커밋 형식을 따릅니다.

실제 GitHub 보호 규칙은 저장소 관리자가 `main`에 설정합니다.
초기화 후에는 PR 승인 1개, 최신 변경 검토, CI 통과, 대화 해결을 요구하고 강제 push·브랜치 삭제를 막습니다.

## 코드와 설정

- 도메인 로직과 엔티티는 담당 서비스가 소유합니다. 서비스 간 계약 변경은 호출자와 함께 검토합니다.
- `.env`, 비밀번호, JWT 키, Webhook URL은 커밋하지 않습니다. 변수 이름만 `.env.example`에 공유합니다.
- 새 API·테이블은 SA/API 명세와 함께 갱신하고 해당 동작을 검증하는 테스트를 추가합니다.
- 인프라 변경은 `docker compose` 시작과 `scripts/smoke.py`까지 확인합니다.
- 인증 기능을 구현하기 전에는 이 구성을 외부에 배포하지 않습니다.
