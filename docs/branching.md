# 브랜치 전략 — GitHub Flow

## 핵심 원칙

1. `master`는 **항상 배포 가능한 상태**를 유지한다. master에 직접 push하지 않는다.
2. 모든 작업은 **짧은 수명의 feature 브랜치**에서 진행한다 (이상적으로 며칠 내 머지).
3. 작업 완료 시 **PR을 열고**, CI(테스트) 통과 후 셀프 리뷰 → 머지한다.
4. 머지 후 feature 브랜치는 삭제한다.
5. 의미 있는 배포 시점마다 **태그(`v0.1.0` 등)**로 릴리스를 마킹한다.

---

## 브랜치 네이밍

| 작업 종류 | 접두사 | 커밋 접두사(기존) | 예시 |
|-----------|--------|-------------------|------|
| 기능 추가 | `feat/` | `기능:` | `feat/menu-management` |
| 버그 수정 | `fix/` | `수정:` | `fix/password-change-404` |
| 리팩터링 | `refactor/` | `리팩터링:` | `refactor/localdatetime-unify` |
| 보안 | `security/` | `보안:` | `security/csrf-enable` |
| 테스트 | `test/` | `테스트:` | `test/ci-db-isolation` |
| 문서/잡일 | `docs/`, `chore/` | `정리:` | `chore/gitignore-cleanup` |

- 슬래시(`/`) 뒤는 영문 소문자 하이픈 케이스(`kebab-case`).
- GitHub 이슈 번호가 있으면 `feat/12-menu-management`처럼 번호를 앞에 붙인다.

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

PR을 열면 GitHub Actions(`ci.yml`)가 자동으로 `./gradlew test`를 실행한다.  
CI가 **통과한 PR만 master에 머지**한다 (브랜치 보호 규칙 참고).

---

## 브랜치 보호 규칙 (GitHub 웹 설정)

`Settings → Branches → master` 에서 아래를 활성화한다.

- Require a pull request before merging
- Require status checks to pass → `test` (CI job 이름)
- Include administrators (관리자도 규칙 적용)
