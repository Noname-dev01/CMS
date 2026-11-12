# 기능 개발 워크플로우 — CMS 고유 규칙

> **8단계 절차의 원본은 전역 명령 `~/.claude/commands/feature.md`다.** 이 문서에는 CMS에서 단계별로 더할 규칙과 사례만 둔다.
> 같은 이름이면 전역 명령이 실행되므로 프로젝트에 `.claude/commands/feature.md`를 두지 않는다. 절차를 바꾸려면 전역 명령을 고친다.
> 계획 리뷰 라운드의 원본은 전역 스킬 `plan-review-loop`, 코드 리뷰는 `code-review-loop`, 커밋·PR은 `commitPR`이다.
>
> 요점 한 줄: **"코드를 읽기 전에 계획하지 않고, 리뷰받지 않은 계획으로 구현하지 않고,
> 승인 없이 스키마를 바꾸지 않고, 테스트 통과로 끝내지 않고 실제 화면으로 확인한다."**

## 경로 판정 — CMS 위험 지점 (최소 목록)

전역 `/feature`는 정찰 직후 정식/경량 경로를 판정한다(위험 영역·외부 계약을 건드리면 정식).
아래 표는 **건드리면 무조건 정식**인 위치만 모은 최소 목록이다. 표에 없다고 경량이 되지는 않는다.
전역 명령의 개념 기준과 "애매하면 정식"이 그대로 적용된다. 새 가드 행·잠금·인가 지점을 만들면 이 표에 함께 추가한다.

| 영역 | 위치 | 이름만 봐서는 모르는 이유 |
|---|---|---|
| 인증·접근 규칙 | `config/**`(`SecurityConfig`·`auth/`·`security/`·`ratelimit/`·`MethodSecurityConfig`) | 로그인 실패 잠금·세션 만료 리스너·레이트리밋 필터 순서가 설정 클래스 안에 있다 |
| 위임 권한 | `admin/permission/**`, 컨트롤러·서비스의 인가 선언(`@PreAuthorize` 등) | 권한 캐시 무효화가 이벤트 리스너로 연결돼 있다 — `admin/permission/CLAUDE.md` |
| 세션·계정 | `admin/session/**`, `admin/member/**`의 서비스·도메인 | 비밀번호 변경 관문·현재 세션 보호가 여기에 모인다 |
| 가드 행 잠금 | `BannerLockRepository`·`FaqLockRepository`(`banner_lock`·`faq_lock`)와 이를 쓰는 서비스 | 평범한 CRUD처럼 보이지만 저장 전체를 가드 행으로 직렬화한다 |
| 행 잠금·버전 | `@Lock`·`FOR UPDATE`·`@Version`이 있는 리포지토리와 이를 호출하는 서비스(`board`·`contentimage`·`media`·`menu`·`page`·`popup`·`site`·`inquiry`·`member`) | 잠금 순서가 패키지별 `CLAUDE.md`에 계약으로 적혀 있어, 호출 순서만 바꿔도 교착이 생길 수 있다 |
| 트랜잭션 경계·이벤트 | `REQUIRES_NEW`·`@TransactionalEventListener` 사용처(`admin/log`·`notification`·`message`·`inquiry/InquiryNotifier`·`media/MediaImageRegistrar`) | 커밋 뒤 실행·독립 트랜잭션·리스너 순서(`@Order`)가 계약이다 |
| 본문 HTML | `common/html/**`, 편집기 툴바·`formats`, 본문을 출력하는 템플릿 | sanitizer 허용 목록과 편집기가 1:1이어야 하고, 출력 경로가 하나 늘면 XSS 표면이 는다 |
| 파일 저장·삭제 | `common/storage/**`, 첨부·이미지 삭제와 회수 경로 | 트랜잭션 정리·보존 규칙과 심볼릭 링크 거부가 여기에 있다 |
| 공개 경로 | `publicweb/support/**`(`PublicSiteInterceptor`·`@PublicFileTransfer`), 공개 노출 조건 쿼리 | 공개 판정이 바뀌면 비공개·예약 글이 노출된다 |
| 스키마 | `src/main/resources/db/migration/**`, 엔티티 매핑 | 마이그레이션 자체가 위험 영역이다 |

경량으로 판정하기 쉬운 예: 문구·스타일만 바꾸는 템플릿 수정, 잠금 없는 조회 화면의 표시 항목 추가, 시험만 추가·정리, 문서.
템플릿이라도 본문 HTML 출력이나 공개 노출 조건을 건드리면 정식이다.

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
