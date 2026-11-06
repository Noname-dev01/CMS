# 기능 개발 워크플로우 — CMS 고유 규칙

> **8단계 절차의 원본은 전역 명령 `~/.claude/commands/feature.md`다.** 이 문서에는 CMS에서 단계별로 더할 규칙과 사례만 둔다.
> 같은 이름이면 전역 명령이 실행되므로 프로젝트에 `.claude/commands/feature.md`를 두지 않는다. 절차를 바꾸려면 전역 명령을 고친다.
> 계획 리뷰 라운드의 원본은 전역 스킬 `plan-review-loop`, 코드 리뷰는 `code-review-loop`, 커밋·PR은 `commitPR`이다.
>
> 요점 한 줄: **"코드를 읽기 전에 계획하지 않고, 리뷰받지 않은 계획으로 구현하지 않고,
> 승인 없이 스키마를 바꾸지 않고, 테스트 통과로 끝내지 않고 실제 화면으로 확인한다."**

## 단계별 CMS 규칙

아래 "예"는 작성 당시의 사례라 지금 코드와 다를 수 있다. 판단 방식을 보여 주는 용도로만 읽는다.

### 1. 정찰
- 재사용할 기존 패턴의 예: `TestMemberLoader` 시드 패턴, `AdminViewAdvice` 모델 주입, `@AllowedRoles` 검증 패턴.
- 테스트 파급의 예: `@WebMvcTest`가 모든 `@ControllerAdvice`를 포함한다 → 새 advice의 의존 빈이 전 슬라이스에 필요하다.
- 핵심 쟁점의 예: Menu에 역할 필드가 없다 → 스키마 변경이 불가피하다.

### 2. 설계
- 과설계 경계의 예: MANAGER 전용 메뉴 개념이 없으므로 2값 enum이 정직한 모델링이다.
- UI 제약의 예: SB Admin 2는 2단까지 → 2단 렌더링 제한.
- 리스크의 예: 공유 fragment 오류는 전 화면 장애다 → 회귀 검증을 필수로 지정한다.

### 3. 적대적 리뷰
- 계획은 `adversarial-review/plan/PLAN-<기능>.md`에 저장하고, 기존 계획서 여부는 `plan/README.md` 인덱스에서 확인한다.
- 구형 명명 `adversarial-review/<기능>-plan.md`는 2026-07-14 폐기했다. 기존 파일은 그대로 두고 신규 계획만 새 경로로 쓴다.
- 개정 이력 작성 사례는 `A-1-activity-log-view-plan.md` v5를 참고한다.
- 사용자가 직접 시작할 때는 `/plan-review-loop adversarial-review/plan/PLAN-<기능>.md`를 실행한다.

### 4. 승인
- 스키마 변경·인가 정책 변경은 CLAUDE.md 규칙대로 사전 고지하고 명시적 승인을 받는다.
- 구현 중 설계를 조정하게 되면 이유와 함께 즉시 보고한다.

### 5. 구현
- 브랜치 규칙은 CLAUDE.md "브랜치 전략"을 따른다. `master`에서 직접 작업하지 않는다.
- 구현 순서: 도메인(엔티티/enum) → DTO·Service → 시드/설정 → 웹 계층(advice/controller) → 템플릿·JS.
- 단계 사이에 `./gradlew compileJava`로 빠르게 컴파일을 확인한다.
- 스키마 변경 시 Flyway 마이그레이션(`src/main/resources/db/migration/`)을 함께 작성한다. 규칙은 `docs/migration-guide.md`를 따른다.

### 6. 테스트
- 검증 범위는 CLAUDE.md "작업 방식" 4번을 따른다 (개발 중 부분 실행, PR 직전 전체 1회).
- TDD를 적용해도 시험 작성 방식은 `src/test/java`의 `CLAUDE.md`를 따른다.
- 목은 시험 대상 밖의 협력 객체나 느리고 외부에 있는 의존성에만 쓴다.
- 쿼리, 트랜잭션, 잠금, 마이그레이션은 실제 DB로 시험한다.
- 목이 호출됐는지만 단언하지 않는다. 호출 자체가 계약인 경우만 예외다.
- Flyway 마이그레이션은 TDD 제외 대상이 아니라 `docs/migration-guide.md`의 시험 정책을 따른다.
- 레거시 호환 케이스의 예: null 정규화. 구조적 파급의 예: 슬라이스 테스트 5곳에 목 빈 추가.
- DB 테스트는 Testcontainers(`MariaDbContainerSupport`)가 일회용 MariaDB를 띄우므로 로컬 DB·env가 필요 없다.
  실패하면 Docker 가용성(Docker Desktop 미기동 등)과 코드 문제를 구분해 진단한다.

### 7. 실기 검증 (Playwright)
- 데이터 왕복은 저장 → DB 확인 → 재조회 → 원복 순서로 확인한다.
- ADMIN과 MANAGER 각각 로그인해 노출 차이와 숨긴 URL 직접 접근 차단을 확인한다.
- 환경을 먼저 의심해야 하는 예: 빈 사이드바가 버그가 아니라 dev DB의 비활성 잔여 데이터인 경우.

### 8. 기록
- 바꾼 패키지의 `CLAUDE.md`를 현행화한다. 여러 패키지에 걸친 사실이면 루트 `CLAUDE.md`도 고친다.
- `plan/README.md` 인덱스에 해당 행이 있으면 완료 표시를 갱신한다.
- 비자명한 이슈는 `docs/troubleshooting.md`에, 세션성 요령은 메모리에 남긴다.

## 안티패턴 (하지 않는 것)
- 코드 안 읽고 계획 작성, 추측으로 API 사용
- 리뷰를 건너뛰고 구현 시작, 리뷰 지적의 무비판 수용 또는 근거 없는 무시
- 승인 없는 스키마/인가 정책 변경, 조용한 설계 변경
- 테스트만 돌리고 "완료" 선언 (화면 미확인)
- 무관한 리팩터링 끼워 넣기
- 에러 패턴만 보고 성급한 조치 (원인 데이터 확인 먼저)
