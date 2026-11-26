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
| 2단계 인증 | `config/auth/Totp*`·`TotpAwareAuthenticationProvider`·`PasskeyAssertionState`·`PasskeyLogin*`(패스키 단독 로그인 필터·서비스·토큰·구성), `admin/member/totp/**`·`admin/member/passkey/**`, `MemberTotpService`·`MemberPasskeyService`·`MemberTotpApiController`·`MemberPasskeyApiController`·`TotpEnrollmentLoginController` | 중간 상태(코드 대기·ADMIN 강제 등록)가 역할 권한 없음에 기대고(새 `AuthenticationProvider` 빈 하나로 꺼진다), 실패 횟수가 둘이며, 변경이 세션 뮤텍스 → 회원 행 잠금 순서다. ADMIN은 필수라 성공 처리기의 토큰 종류 대조가 3가지다. 패스키 단독 로그인은 성공 처리기의 네 번째 토큰 종류(TOTP 등록 세대 대조)이고 검증 트랜잭션의 첫 작업이 회원 행 잠금이어야 한다(스냅샷) |
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
- 검증 범위는 CLAUDE.md "작업 방식" 4번을 따른다 (개발 중에는 관련 시험만, 전체 시험은 일반 변경이면 PR CI에 맡기고 공용 코드·정식 경로이면 로컬에서 1회).
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

## 병행 작업 (worktree 여러 개)

### 시작
- 이슈의 선행 조건과 `deferred`·`blocked` 라벨을 확인한 뒤 worktree를 만든다.
- 큰 작업의 설계 질문을 먼저 끝내고 작은 작업을 시작한다(질문이 한꺼번에 몰리지 않게).

### 세션끼리 섞이지 않게
- **포트**: 세션마다 다른 `SERVER_PORT`를 쓰고(8081부터), 띄우기 전에 비어 있는지 확인한다.
- **앱 종료**: 띄울 때 그 포트를 쓰는 PID를 기록하고 끌 때 대조한다. 포트만 보고 종료하지 않으며, 자기 worktree의 프로세스인지 확인되지 않으면 사용자에게 묻는다. 실기 검증이 끝나면 바로 끈다.
- **dev DB**: 모든 세션이 공유한다. 임시 데이터는 세션을 구분할 수 있는 이름(예: 이슈 번호)으로 만들고 끝나면 자기 것만 지운다.
- **완료 표시**: 이전 작업의 계획 인덱스·계획서 완료 표시는 먼저 PR을 만드는 세션 하나만 한다. 열린 PR에 같은 수정이 있으면 하지 않는다.
- **`master` 반영**: 다른 PR이 머지될 때마다 하지 않고, 코드 리뷰 뒤(로컬 전체 시험이 필요한 변경이면 그 시험 전) 한 번 한다. 충돌이 예상되면 앞당긴다. PR을 만든 뒤 `master`가 앞서면 `strict` 때문에 다시 반영한다. 재시험 범위는 루트 `CLAUDE.md` 작업 방식 4항을 따른다.
- **함정 공유**: 다른 세션도 겪을 실행·검증 함정은 작업이 끝날 때가 아니라 발견 즉시 메모리에 남긴다.

### 동시 실행 제한
개수 제한은 두지 않는다. 아래 신호가 나오면 원인이 자원인지 단독 재실행으로 확인하고, 자원이면 제한을 제안한다.

| 신호 | 대응 |
|---|---|
| 겹쳐 돌린 시험이 실패했는데 단독 실행에서는 통과 | 로컬 전체 시험을 동시에 1개로 제한 |
| 로컬 전체 시험(브라우저 제외 + 브라우저)이 15분 초과(2026-11-23 실측 10~12분) | 메모리·CPU를 재고 제한 검토 |
| 메모리 부족 오류, 시험용 DB 기동 실패, PC가 눈에 띄게 느려짐 | 겹친 실행을 줄이고 사용자에게 보고 |

### 팀장·팀원 운영 (2026-11-26)

여러 작업을 한꺼번에 돌릴 때 메인 체크아웃 세션 하나가 **팀장**이 되어 worktree를 만들고 그 안에 **팀원** 세션을 띄워 맡긴다. 기계 장치는 Orca `orchestration` 스킬이 원본이고(명령은 Orca 1.4.277 `--help`로 확인), 여기에는 CMS에서 더할 규칙만 둔다.

- **역할**: 팀장은 배정·조율·사용자 창구만 한다 — 코드를 고치지 않고 메인 체크아웃에서 브랜치를 만들거나 바꾸지 않는다. 팀장 자신이 문서 등을 직접 고쳐야 하면 자기 worktree를 따로 만들어 들어간다. 팀원은 **worktree 하나당 한 명**이고 그 작업의 `/feature` 전 단계(정찰~커밋·PR)를 맡는다. 팀원 수는 정하지 않고 위 "동시 실행 제한" 신호를 따른다(`orchestration`은 한 번에 4명 안팎을 권한다).
- **시작** — 어느 방식이든 **worktree를 먼저 만든다**: `orca worktree create --name <이름> --branch <브랜치 전략의 접두사/이름> --base-branch origin/master --no-parent`(`<repoId>`·경로는 결과의 `id`, 또는 `orca repo list --json`). `worker-start --worktree new-top-level`은 `--branch`가 없어 Orca 기본 접두사가 붙은 브랜치(예: `Noname-dev01/<이름>`)를 만들므로 쓰지 않는다(2026-11-26 시험).
  - 결과를 팀장이 받아야 하면(기본) `orca orchestration worker-start --environment local --spec-file <인수인계 문서> --task-title "<제목>" --worktree id:<repoId>::<경로> --agent claude`. 팀원이 끝나면 `worker_done`으로 팀장이 깨어난다(`orca orchestration check …` 안내가 온다). 실패하면 `escalation`이 온다 — 예: 사용량 한도(`usageLimitReached`)는 `worker-stop --dispatch <id>` 뒤 `worker-start --task <taskId> --retry-of <dispatchId> --worktree id:… --agent claude`로 다시 시작한다(`--retry-of`는 배치를 물려받지 않으므로 `--worktree`·`--agent`를 다시 준다). 끝난 팀원 터미널은 남아 있으니 아무도 그 대화를 쓰지 않으면 놓아 준다.
  - 넘기고 끝이면 인수인계 문서를 worktree 루트에 두고 `orca agent launch --agent claude --worktree id:<repoId>::<경로> --prompt "<문서를 읽고 /feature로 진행>"`. 이후 지시는 `SendMessage`(세션 이름은 `ListAgents`)로 한다 — 결과는 돌아오지 않는다.
  - 2026-11-26 확인(Orca 1.4.277): 위 두 방식 모두 실제로 실행해 팀원 시작·`worker_done` 수신(기본 방식)·재시도까지 확인했다.
- **배정 전 충돌 점검**: 메인 체크아웃과 모든 worktree의 `git status --short`·`git diff --stat origin/master...<브랜치>`를 모아 새 작업이 고칠 파일과 겹치는지 본다. 겹치면 인수인계 문서에 적고(누가 어떤 변경을 하는지), 순서를 바꿀지 사용자에게 묻는다. 예: 2026-11-26 SOLID 1번과 시험 데이터 누수 작업이 둘 다 `LocalDiskFileStorageTest`를 고쳤다.
- **자원 배정**: 팀원마다 앱 포트(8081부터)와 실기용 일회용 DB 포트(3397부터)를 겹치지 않게 정해 인수인계 문서에 적는다. dev DB는 공유이므로 실기는 일회용 DB를 권한다.
- **인수인계 문서**(`HANDOFF-<이름>.md`, 팀원 worktree 루트, **커밋하지 않고 읽은 뒤 지운다**):
  1. 무엇을·왜 — 배경, 이미 받은 사용자 결정(다시 묻지 않게)
  2. 확인된 사실 — 기준 커밋·파일·줄 번호와 "다시 확인할 것" 표시(추측과 사실을 나눈다)
  3. 예상 위험 영역·경로(정식/경량) — 판정은 팀원이 직접 한다
  4. 병행 주의 — 충돌 점검 결과, 다른 세션이 쓰는 위치(메인 체크아웃은 건드리지 않는다)
  5. 배정 자원 — 앱 포트·일회용 DB 포트
  6. 첫 할 일 — 읽을 문서, `/feature` 호출 인자, 남은 단계 순서
- **질문 경계**: 팀원의 `ask`(또는 `SendMessage`) 중 **사실 확인**(코드·문서·다른 worktree 상태)은 팀장이 확인해 답한다. **사용자 결정**(경로 확인, grilling, 계획 승인, 커밋·PR·이슈 등록 확인, 범위 변경)은 팀장이 대신 답하지 않는다 — 팀원이 사용자에게 직접 묻게 하거나, 팀장이 질문 창으로 사용자에게 물어 그 답을 그대로 전한다.
- **금지(사례)**: 2026-11-26 팀장 세션이 메인 체크아웃에서 `git switch -c`로 작업 브랜치를 만들자, 같은 디렉터리에서 일하던 다른 세션의 작업 트리가 그 브랜치로 함께 옮겨졌다(즉시 되돌림). 브랜치 작업은 반드시 worktree에서 한다.

### worktree 정리 (머지 확인 뒤)
worktree와 로컬 브랜치 삭제는 Orca의 "병합된 워크스페이스 삭제"에 맡긴다(2026-11-24 PR #311로 확인). 마지막 활동 1시간 뒤의 매시간 검사에서 지워지므로 기다리지 않는다.

- **"무시된 파일이 있는 워크스페이스 유지"는 끄지 않는다.** 메인 폴더의 **같은 경로에 같은 내용**이 없는 파일(스크린샷·첨부·`.env.dev`)이 하나라도 있으면 worktree가 유지된다(2026-11-26 PR #323으로 확인). 머지 확인 뒤 아래처럼 처리한다.
  - `docs/screenshots/**`: 메인 폴더의 같은 경로로 복사한다(worktree의 원본은 둔다). 이름이 같고 내용이 다르면 덮어쓰지 않고 묻는다.
  - `data/**`: 복사하거나 바로 지우지 않는다. 출처(시험·실기)와 dev DB 참조를 확인해 목록을 보이고 삭제 승인을 받는다. 2026-11-26부터 Gradle로 돌린 시험은 `data/`가 아니라 `build/test-file-storage`에 쓰므로(PLAN-test-data-leak.md), 그 뒤에 생긴 `data/**` 파일은 실기(`bootRun`)나 IDE 단독 실행의 것이다.
  - 그 밖의 파일(`.env.dev` 등): 건드리지 않고 보고한다.
  - 처리한 뒤 `orca workspace cleanup preview`로 사유가 사라졌는지 확인한다.
- **codex broker는 자동 정리로 끝난다고 보지 않는다**(확인하지 못함). 그 worktree에서 codex를 썼으면 머지 확인 뒤 아래 3·4번으로 종료한다.
- 남아 있으면 `orca workspace cleanup report`(지난 검사), `orca workspace cleanup preview`(현재 판정)로 사유를 확인한다. 둘 다 조회만 한다.

자동 정리가 유지했거나 즉시 지워야 하면 사용자 요청을 받아 직접 정리한다.

1. 커밋 전 변경이 없고 세션이 idle인지 확인한다. 작업 브랜치에 커밋이 남아 있으면 Squash 머지 커밋과 내용이 같은지 비교한다. 하나라도 아니면 멈추고 보고한다.
2. 무시된 파일을 위 목록대로 처리한다.
3. 명령줄의 `--cwd`가 그 worktree인 codex broker(`app-server-broker.mjs`)를 찾아 PID와 자식 프로세스를 보이고 종료 승인을 받는다.
4. 종료 직전에 PID와 명령줄을 다시 확인하고 그 프로세스 트리만 종료한다.
5. `orca worktree rm`을 `--force` 없이 실행하고, 로컬 브랜치는 `git branch -d`로 지운다.

## 머지 예약

기본은 사용자가 직접 머지한다. PR 개수와 관계없이 아래 자격이 있는 PR만 예약할 수 있다.

| PR 종류 | 예약 |
|---|---|
| `/feature` 경량 경로(시험 코드만 바꾼 PR 포함) | 가능 |
| 문서만 바꾼 PR(`CLAUDE.md` 등 작업 규칙 포함) | 가능 |
| 정식 경로, 로컬 전체 시험이 필요한 변경(루트 `CLAUDE.md` 작업 방식 4항) | 직접 머지 |
| CI·훅·빌드 설정, Dependabot | 직접 머지(사용자가 내용을 보고 원하면 예약) |

1. PR 제목·라벨을 묻는 창에서 예약 여부를 함께 묻고(기본은 예약 안 함), **명시적으로 승인한 PR만** `gh pr merge <번호> --auto --squash`로 예약한다. 다음이면 자격이 있어도 예약하지 않는다: 재리뷰받지 않은 수정이 있음, 화면을 바꿨는데 사용자가 실기 결과를 보지 않음, 경로 판정이 애매함.
2. 예약한 PR은 하나씩 처리한다. 앞 PR이 머지되면 다음 PR에 최신 `master`를 merge 커밋으로 반영해 push하고(강제 push 금지) CI를 지켜본다 — `strict`라 뒤처진 PR은 예약돼 있어도 머지되지 않는다.
3. CI 실패·충돌이 나면 `gh pr merge <번호> --disable-auto`로 예약을 풀고 원인을 보고한다. 고친 뒤에는 다시 승인받아 예약한다.
4. 예약한 뒤 그 PR의 코드를 고치게 되면 먼저 예약을 푼다(`master` 반영은 제외).
5. 머지가 확인되면 `commitPR` 9단계(머지 후 정리)를 한다. worktree 정리의 프로세스 종료는 그대로 승인받는다.

세션을 닫으면 2번의 반영이 멈춘다. 이미 최신인 예약 PR은 세션과 무관하게 머지된다.
예약해 머지한 변경을 되돌리는 일이 생기면 위 기준을 다시 좁힌다.

## 안티패턴 (하지 않는 것)
- 코드 안 읽고 계획 작성, 추측으로 API 사용
- 리뷰를 건너뛰고 구현 시작, 리뷰 지적의 무비판 수용 또는 근거 없는 무시
- 승인 없는 스키마/인가 정책 변경, 조용한 설계 변경
- 테스트만 돌리고 "완료" 선언 (화면 미확인)
- 무관한 리팩터링 끼워 넣기
- 에러 패턴만 보고 성급한 조치 (원인 데이터 확인 먼저)
