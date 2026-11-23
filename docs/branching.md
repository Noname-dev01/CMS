# 브랜치 전략 — GitHub Flow

## 핵심 원칙

1. `master`는 **항상 배포 가능한 상태**를 유지한다. master에 직접 push하지 않는다.
2. 모든 작업은 **짧은 수명의 feature 브랜치**에서 진행한다 (이상적으로 며칠 내 머지).
3. 작업 완료 시 **PR을 열고**, CI(테스트) 통과 후 셀프 리뷰 → 머지한다.
4. 머지 후 feature 브랜치는 삭제한다.
5. 의미 있는 배포 시점마다 **태그(`v0.1.0` 등)**로 릴리스를 마킹한다.

---

## 브랜치 네이밍

| 작업 종류 | 접두사 | 커밋 접두사(기존) | PR 종류 라벨 | 예시 |
|-----------|--------|-------------------|--------------|------|
| 기능 추가 | `feat/` | `기능:` | `enhancement` | `feat/menu-management` |
| 버그 수정 | `fix/` | `수정:` | `bug` | `fix/password-change-404` |
| 리팩터링 | `refactor/` | `리팩터링:` | `refactor` | `refactor/localdatetime-unify` |
| 보안 | `security/` | `보안:` | 성격대로 `enhancement`·`bug`·`refactor` + 영역 `security` | `security/csrf-enable` |
| 테스트 | `test/` | `테스트:` | `test` | `test/ci-db-isolation` |
| 문서 | `docs/`, `chore/` | `문서:` | `documentation` | `chore/roadmap-member-permission-pr-b` |
| 잡일·정리 | `chore/` | `정리:` | `chore` | `chore/gitignore-cleanup` |

- 슬래시(`/`) 뒤는 영문 소문자 하이픈 케이스(`kebab-case`).
- GitHub 이슈 번호가 있으면 `feat/12-menu-management`처럼 번호를 앞에 붙인다.

### PR 라벨 (2026-11-16 — Conventional Commits 참고)

PR 라벨은 **종류 + 영역** 두 축으로 단다. 커밋 접두사는 한국어 그대로 두고, 라벨만 [Conventional Commits 1.0.0](https://www.conventionalcommits.org/en/v1.0.0/)의 타입 구분(`feat`·`fix`와 권장 타입 `refactor`·`test`·`docs`·`chore` 등)에 맞춘다.

- **종류(정확히 1개)**: 위 표의 "PR 종류 라벨". 커밋 접두사에서 정한다.
  - 표준에서 보안은 종류가 아니라 영역이다. 그래서 `보안:` PR은 변경 성격대로 종류를 고르고(새 기능이면 `enhancement`, 결함 수정이면 `bug`, 기능 제거·구조 변경이면 `refactor`) 영역 `security`를 함께 단다.
- **영역(0~1개, 해당할 때만)**: `security`(인증·인가·암호화·공격 방어), `ops`(배포·CI·인프라 — Compose, `scripts/`, `Dockerfile`, 워크플로).
- **관련 이슈가 있으면** 그 이슈의 종류·영역 라벨을 우선 따른다.
- **PR에 달지 않는 라벨**:
  - 이슈 상태 라벨 `blocked`·`deferred`
  - Dependabot 자동 라벨 `dependencies`·`java`·`docker`·`docker_compose`·`github_actions`
    - 수동으로 의존성 버전을 올리는 PR은 `dependencies`를 단다.
  - 이슈 관리용 `duplicate`·`invalid`·`wontfix`·`question`·`good first issue`·`help wanted`
- 예: `보안: 관리자 2단계 인증(TOTP) 도입` → `enhancement` + `security`, `정리: Locale.ROOT 컨벤션 시험 추가` → `chore`, `문서: …` → `documentation`.

---

## 표준 작업 흐름

```bash
# 1. master 최신화 후 브랜치 생성
git switch master && git pull
git switch -c feat/menu-management

# 2. 작업 + 커밋 (한국어 타입 접두사 유지)
git commit -m "기능: 메뉴 관리 CRUD 추가"

# 3. 원격 푸시 후 PR 생성
git push -u origin feat/menu-management
gh pr create --fill

# 4. CI 통과 확인 → 셀프 머지 (Squash)
gh pr merge --squash --delete-branch

# 5. 로컬 정리
git switch master && git pull
```

---

## 머지 방식

**Squash merge** 사용 — feature 브랜치의 중간 커밋을 하나로 압축하여 master 히스토리를 "기능 단위"로 유지한다.

---

## CI

PR을 열면 GitHub Actions(`ci.yml`)가 세 job을 병렬로 실행한다.

- `test`: `./gradlew test -PbrowserTests=exclude` — 브라우저 시험을 뺀 전체
- `browser-test`: `./gradlew test -PbrowserTests=only` — 브라우저 E2E 시험(`BrowserTestSupport` 상속 클래스, `@Tag("browser")`)만. Playwright 설치(약 2분)도 이 job만 한다(2026-10-22 분리 — 한 job일 때 약 7분이던 PR 대기를 줄인다)
- `prod-smoke`: prod 이미지 빌드·기동·보안 응답 검증, 백업·복구 왕복, 이미지 취약점 스캔(상세: `docs/deployment.md` "CI 배포 게이트")

CI가 **통과한 PR만 master에 머지**한다 (브랜치 보호 규칙 참고). 일반 변경은 로컬 전체 시험 없이 이 CI가 전체 시험을 맡으므로(루트 `CLAUDE.md` 작업 방식 4항), CI가 실패한 채로 머지하지 않는다.

---

## 브랜치 보호 규칙 (GitHub 웹 설정)

`Settings → Branches → master` 에서 아래를 활성화한다.

- Require a pull request before merging
- Require status checks to pass → `test`, `browser-test`, `prod-smoke` (CI job 이름) — `prod-smoke`는 이 설정으로 머지 차단 게이트로 작동한다(2026-09-29 등록됨 — 실패한 PR은 머지할 수 없다). `browser-test`(2026-10-22 분리)도 등록해야 브라우저 시험 실패가 머지를 막는다
- Require branches to be up to date before merging(`strict`, 2026-11-23): 최신 `master`를 반영한 상태로 CI를 통과해야 머지할 수 있다. 다른 PR이 먼저 머지되면 `master`를 반영하고 CI를 다시 통과시킨다 — PR 화면의 [Update branch]를 쓰면 원격 브랜치가 앞서므로 로컬에서 pull한다. Dependabot PR에도 적용된다
- Include administrators (관리자도 규칙 적용)

---

## IntelliJ IDEA로 GitHub Flow 수행

CLI 대신 IntelliJ UI로 동일한 워크플로를 수행하는 방법.

### 사전 1회 설정: GitHub 계정 연결

`File → Settings → Version Control → GitHub → +` → "Log In via GitHub..." 클릭 → 브라우저에서 인증  
연결 후 PR 생성·조회를 IDE 안에서 할 수 있다.

### 단계별 조작

| CLI 명령 | IntelliJ UI |
|----------|-------------|
| `git switch master && git pull` | 우하단 **Git 브랜치 위젯** → `master` → Checkout. 이어서 툴바 ⬇ 아이콘(Update Project) 또는 `Ctrl+T` |
| `git switch -c feat/...` | 브랜치 위젯 → **New Branch** → 이름 입력(`feat/menu-management`) → "Checkout branch" 체크 후 Create |
| `git add . && git commit -m "..."` | `Ctrl+K` (Commit 창) → 변경 파일 체크 → 한국어 접두사 커밋 메시지 입력 → **Commit** |
| `git push -u origin feat/...` | `Ctrl+Shift+K` → **Push** |
| `gh pr create` | Push 직후 IDE 상단 알림 **"Create Pull Request"** 클릭, 또는 좌측 **Pull Requests 도구창** → `+` → 제목/본문 작성 → Create |
| CI 통과 확인 | Pull Requests 도구창에서 해당 PR 선택 → ✓/✗ 체크 상태 확인 |
| `gh pr merge --squash --delete-branch` | PR 도구창 → **Merge** 드롭다운 → **Squash and merge** → 머지 후 "Delete branch" |
| `git switch master && git pull` | 브랜치 위젯에서 `master` Checkout → `Ctrl+T` |

### 핵심 단축키 (Windows)

| 단축키 | 동작 |
|--------|------|
| `Ctrl+K` | Commit 창 열기 |
| `Ctrl+Shift+K` | Push |
| `Ctrl+T` | Update Project (pull) |
| `Alt+0` | Commit 도구창 토글 |
| `Alt+9` | Git 로그/히스토리 도구창 |

### 주의점

1. **Squash 선택지가 안 보이면** — GitHub 저장소 `Settings → General → Pull Requests`에서 "Allow squash merging"이 켜져 있어야 IDE 머지 버튼에 Squash 옵션이 노출된다.
2. **master 직접 push 거부는 IntelliJ에서도 동일** — 브랜치 보호는 서버 측 강제라 IDE에서 push해도 똑같이 reject된다.
3. **머지는 GitHub 웹에서 해도 무방** — IntelliJ는 PR 생성까지만 쓰고, 머지는 웹에서 "Squash and merge" 클릭하는 방식도 된다.
