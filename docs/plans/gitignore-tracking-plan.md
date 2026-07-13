# gitignore 세분화: 워크플로우 스킬·지침 파일 git 추적 전환

> 작성일: 2026-07-14 · 상태: **실행 완료** (2026-07-14 실행 — 검증 내역은 하단 "실행 결과" 참조. 커밋은 사용자 결정 대기)

## Context

`.claude/`(스킬·커맨드), `CLAUDE.md`, `AGENTS.md`, `development-workflow.md` 등 개발 워크플로우의 핵심 파일들이 전부 gitignore 대상이라 저장소를 클론하거나 PC를 옮기면 `/feature → /commitPR` 파이프라인 전체가 사라진다. 이를 추적 대상으로 전환하되, `.claude/settings.json`에 하드코딩된 Discord 웹훅 URL(시크릿)은 먼저 환경변수 참조로 분리한다.

사용자 결정 사항 (2026-07-14 확정):
- `development-workflow.md`는 **`docs/`로 이동** (adversarial-review/는 "로컬 전용" 원칙 유지)
- 로드맵·계획서(`project-direction-roadmap.md`, `plan/PLAN-*.md`)는 **로컬 전용 유지** (추적 안 함)
- `settings.json`은 **웹훅을 env 참조로 바꾼 뒤 추적** (`DISCORD_WEBHOOK_URL` 사용자 환경변수 이미 설정 확인됨 — `codex-discord-notify.ps1`과 동일 패턴)

## 변경 내용

### 0. 이 계획 문서를 `docs/`에 저장 (사용자 요청)

이 계획 전문을 `docs/plans/gitignore-tracking-plan.md`로 저장한다 (`docs/plans/` 디렉터리 신규 생성). 실행 완료 후 결과(검증 내역)를 같은 파일에 갱신한다.

### 1. `.claude/settings.json` — 웹훅 시크릿 제거

Notification·Stop 훅 두 곳의 커맨드에서 하드코딩된 `https://discord.com/api/webhooks/1514.../...` URL을 `"$DISCORD_WEBHOOK_URL"` 참조로 교체한다. 변수 미설정 환경에서 훅이 에러를 내지 않도록 가드 추가:

```
if [ -n "$DISCORD_WEBHOOK_URL" ]; then BRANCH=$(...) && printf ... | curl -s ... "$DISCORD_WEBHOOK_URL"; fi
```

(훅은 `"shell": "bash"`이므로 POSIX 문법. Git Bash는 Windows 사용자 환경변수를 상속한다 — 적용 후 Bash 도구로 변수 존재를 확인한다. 값 자체는 출력 금지.)

### 2. `development-workflow.md` 이동 + 참조 갱신

- `adversarial-review/development-workflow.md` → `docs/development-workflow.md` (비추적 파일이므로 단순 `mv`)
- 참조 갱신 (전체 grep으로 확인된 유일한 참조): `.claude/commands/feature.md:7` — `adversarial-review/development-workflow.md` → `docs/development-workflow.md`
- 문서 내부의 계획서 경로(`adversarial-review/plan/PLAN-*.md`)는 그대로 유효하므로 수정 불필요

### 3. 쓰레기 파일 삭제

- `.claude/skills/bash.exe.stackdump` — `.claude/skills/` 추적 전환 시 딸려 들어가므로 반드시 삭제
- 루트 `bash.exe.stackdump`

### 4. `.gitignore` 수정

```diff
 ### Claude Code ###
-.claude/
+.claude/*
+!.claude/skills/
+!.claude/commands/
+!.claude/settings.json
 .mcp.json

 /.playwright-mcp
 /adversarial-review
-/AGENTS.md
-/bash.exe.stackdump
-/CLAUDE.md
-/scripts/codex-discord-notify.ps1
+*.stackdump
```

- `.claude/*` + `!` 예외 구조: 디렉터리 자체(`.claude/`)를 제외하면 하위 예외가 무효라서 `/*` 형태가 필수
- `settings.local.json`은 `.claude/*`에 걸려 계속 제외 (의도)
- `/adversarial-review`는 유지 (로드맵·계획서 로컬 전용 결정)
- `/bash.exe.stackdump` → `*.stackdump`로 일반화 (`.claude/skills/` 등 어디서 재발해도 차단)
- `.mcp.json` 규칙은 무해하므로 유지

### 5. 추적 전환으로 새로 untracked가 되는 파일 (커밋은 이번 작업에서 하지 않음)

- `CLAUDE.md`, `AGENTS.md`
- `.claude/settings.json`, `.claude/skills/*/SKILL.md` (6개), `.claude/commands/*.md` (3개)
- `scripts/codex-discord-notify.ps1`
- `docs/development-workflow.md`, `docs/plans/gitignore-tracking-plan.md`

**커밋 없음**: 현재 `feat/password-reset` 브랜치에 진행 중 변경이 있어 섞이면 안 된다. 커밋 시점·브랜치(별도 `chore/` 권장)는 사용자가 `/commitPR`로 결정.

## 검증

1. `git status --porcelain` — 위 5번 목록이 untracked(`??`)로 나타나는지 확인
2. `git check-ignore -v .claude/settings.local.json .env.dev adversarial-review/project-direction-roadmap.md` — 계속 ignore되는지 확인
3. Grep으로 저장소 전체에 `discord.com/api/webhooks` 문자열이 남아 있지 않은지 확인
4. Bash 도구에서 `[ -n "$DISCORD_WEBHOOK_URL" ] && echo OK` — 훅 실행 환경에서 변수 접근 가능한지 확인 (값 미출력)
5. `docs/development-workflow.md` 존재 + `adversarial-review/`에 원본 부재 확인, `feature.md`의 참조 경로 갱신 확인

## 실행 결과

2026-07-14 실행 완료. 검증 5개 항목 전부 통과:

1. `git status --porcelain -uall` — 예상 목록과 정확히 일치: `CLAUDE.md`, `AGENTS.md`, `.claude/settings.json`, `.claude/skills/*/SKILL.md` 6개, `.claude/commands/*.md` 3개, `scripts/codex-discord-notify.ps1`, `docs/development-workflow.md`, `docs/plans/gitignore-tracking-plan.md`가 untracked(`??`)로 확인됨
2. `git check-ignore -v` — `.claude/settings.local.json`(`.claude/*` 규칙), `.env.dev`(`.env*`), `adversarial-review/project-direction-roadmap.md`(`/adversarial-review`) 모두 계속 ignore됨
3. 저장소 전체 grep — 웹훅 시크릿 전문은 어디에도 없음 (이 문서의 마스킹된 `1514.../...` 표기만 존재)
4. Bash 환경에서 `$DISCORD_WEBHOOK_URL` 존재 확인 (`OK`, 값 미출력)
5. `docs/development-workflow.md` 존재 + `adversarial-review/` 원본 부재 + `feature.md` 참조 경로 갱신 확인. stackdump 2개(루트, `.claude/skills/`) 삭제 확인

**커밋은 하지 않음** (계획대로) — 커밋 시점·브랜치는 사용자가 `/commitPR`로 결정.
