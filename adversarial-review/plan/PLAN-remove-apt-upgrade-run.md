# PLAN — Dockerfile 임시 apt 상향 RUN 제거 (이슈 #248)

## 개정 이력
- v1 (2026-11-17): 최초 작성
- v2 변경 (리뷰 1라운드): ① F3 근거를 `--only-upgrade` 옵션 해석에서 실측으로 교체 — 새 digest에서 `RUN` 실행 전후 `dpkg-query -W` 136개가 동일, `0 upgraded`(옵션은 의존성 상향을 막지 않는다는 지적 수용) ② S3·F1을 실제 게이트(`ignore-unfixed: true`, Trivy v0.70.0)에 맞게 고치고 F1 일차 방어를 digest 전체 비교 + S2 설치 버전으로 둠 ③ 역방향 되돌리기 실패 모드 F5 추가 ④ 전체 digest·플랫폼 명시, arm64 미검증을 결과 문서에 남기도록 F2 보강. 기각 없음. Ubuntu CVE 공지의 수정 버전 표기는 직접 확인하지 않아 "미확인"으로 둔다
- v3 변경 (리뷰 2라운드): §5 로컬 시험 전면 생략을 철회 — 개발 중엔 S2로 확인하고 PR 직전 전체 시험(`-PbrowserTests=exclude`) 1회는 CLAUDE.md 4항대로 수행. 기각 없음

## 1. 배경
- #204(`2fd63fe`, 2026-11-05)는 CVE-2026-109518(HIGH, `apt`) 때문에 `prod-smoke`의 Trivy가 막히자, 공식 이미지에 수정판이 없어서 `Dockerfile` 런타임 단계에 `apt`·`libapt-pkg6.0t64`만 `--only-upgrade`하는 임시 `RUN`을 넣었다.
- 이슈 #248의 완료 기준: ① 공식 런타임 이미지의 `apt`가 수정판(2.8.4ubuntu0.1) 이상 ② 임시 `RUN`을 지운 뒤 `prod-smoke`·Trivy 통과.
- 2026-11-17 Dependabot PR #271이 `eclipse-temurin:21-jre-noble` digest를 `0eda5830…` → `0d57a8e9…`로 갱신했다(변경 파일 `Dockerfile` 한 줄, CI `test`·`browser-test`·`prod-smoke` SUCCESS — 이 결과는 임시 `RUN`이 남은 상태 기준이다).

## 2. 확인한 사실 (출처)
| 전제 | 결과 | 출처 |
|---|---|---|
| 새 digest | `eclipse-temurin:21-jre-noble@sha256:0d57a8e90c2bb844c5a8e1b2b71e11eff06dad7edcfd803cd21ed4ae203b2515` | `gh pr diff 271` |
| 새 digest의 `apt` | `2.8.4ubuntu0.1` | 실측 2026-11-17(linux/amd64): `docker run --rm --entrypoint sh eclipse-temurin@sha256:0d57a8e9…2515 -c "dpkg-query -W apt libapt-pkg6.0t64"` → `apt	2.8.4ubuntu0.1` |
| 새 digest의 `libapt-pkg6.0t64` | `2.8.4ubuntu0.1` | 같은 실측 → `libapt-pkg6.0t64:amd64	2.8.4ubuntu0.1` |
| 새 digest에서 `RUN`의 효과 | **없음** — `0 upgraded, 0 newly installed`, 실행 전후 `dpkg-query -W` 136줄 `diff` 동일 | 실측 2026-11-17(`uname -m` = x86_64), 같은 이미지에서 `RUN`과 같은 `apt-get` 명령 실행 |
| 수정판 기준 2.8.4ubuntu0.1 | 이슈 #248·#204 기재값 | Ubuntu CVE 공지 원문은 **미확인** |
| CI Trivy 버전 | v0.70.0(`aquasecurity/trivy-action@ed142fd…` v0.36.0의 기본값) | 리뷰 1라운드 제시(action.yaml), 결과 문서에 CI 로그로 재확인 |
| 새 digest의 OS·JRE | Ubuntu 24.04.5 LTS, OpenJDK 21.0.13 | 같은 실측(`/etc/os-release`, `java -version`) |
| 실측 플랫폼 | linux/amd64(로컬 Docker Desktop). CI 러너 `ubuntu-latest`도 amd64 | 로컬 실측. arm64 매니페스트는 **미확인**(운영 플랫폼도 미정 — #238) |
| 옛 digest의 `apt` | 2.8.3(취약) | `Dockerfile:19` 주석, #204·#219 확인 이력 |
| 런타임 digest 참조처 | `Dockerfile:16` 한 곳 | `grep -rn 0eda5830`(build·.git 제외) |
| Trivy 게이트 | `severity: CRITICAL,HIGH`, `ignore-unfixed: true`, `exit-code: 1`, 예외 `.trivyignore.yaml`(현재 비어 있음) | `.github/workflows/ci.yml:117-128`, `.trivyignore.yaml` |

## 3. 설계 결정
1. **#271을 먼저 머지하고 제거는 별도 PR로 한다**(사용자 결정 2026-11-17).
   - 이유: 커밋이 나뉘어 이력이 분명하고, Dependabot의 정상 경로를 그대로 따른다. 단 되돌리기는 독립적이지 않다 — #271만 되돌리면 취약 상태가 된다(F5).
   - 대가: 제거 PR은 반드시 #271이 머지된 master에서 분기한다. 옛 digest에서 `RUN`만 지우면 CVE가 되살아난다 — 실패 모드 F1.
2. **`RUN`과 그 주석 두 줄만 지운다.** 다른 OS 패키지 상향 장치나 "자동 확인 스크립트"는 만들지 않는다.
   - 이유: 과설계 방지. 같은 상황이 다시 생기면 `docs/deployment.md` "스캔 실패 시 절차"의 일반 규칙으로 충분하다.
3. **`docs/deployment.md:337`은 일반 규칙을 남기고 "현재 …" 괄호만 고친다.**
   - 이유: "공식 이미지가 재빌드되지 않았으면 그 패키지만 상향할 수 있다"는 절차는 앞으로도 유효하다. 현재 적용 중인 사례만 사라진다.
   - 괄호 대체 문구: "(2026-11-05~11-17 `apt`·`libapt-pkg6.0t64` CVE-2026-109518 사례 — #204에서 넣고 #248에서 지웠다. 이 단계는 digest로 고정되지 않으므로 Dependabot digest PR을 받을 때 공식 이미지의 버전을 확인해 지운다. 지운 뒤 베이스 digest를 옛 값으로 되돌릴 때는 제거 커밋부터 되돌리거나 상향 단계를 함께 복원하고 이미지의 패키지 버전을 다시 확인한다)".

## 4. 작업 단계
1. #271 머지(squash) — 사용자 승인 뒤
2. master 갱신 → `chore/remove-apt-upgrade-run` 브랜치
3. `Dockerfile` 19~23행(주석 2줄 + `RUN` 3줄)과 그 뒤 빈 줄 1개 제거
4. `docs/deployment.md:337` 괄호 수정
5. 로컬 검증(§5), 계획 문서·인덱스 갱신 → `code-review-loop` → `commitPR`(`Closes #248`)

## 5. 성공 기준
- S1: master의 `Dockerfile` 런타임 `FROM`이 `0d57a8e9…`이고 `apt-get` 문자열이 없다(`grep -c apt-get Dockerfile` = 0)
- S2: 로컬 `docker build`로 만든 이미지 안의 `dpkg-query -W apt libapt-pkg6.0t64`가 둘 다 `2.8.4ubuntu0.1`
- S3: PR CI `prod-smoke`의 Trivy 단계 통과 — 의미는 "그 시점 Trivy DB 기준으로 수정판이 있는 HIGH/CRITICAL 없음"(`ignore-unfixed: true`)이며, HIGH/CRITICAL 전체 0건이 아니다. 로컬 Trivy는 돌리지 않는다(CI가 같은 이미지 경로의 정본)
- S4: PR CI `test`·`browser-test`·`prod-smoke` 모두 SUCCESS
- 개발 중 반복: Java 코드 변경이 없어 Gradle 시험 대신 S2(로컬 이미지 빌드·버전 확인)로 확인한다. 이미지의 부팅·보안 응답·백업 왕복은 PR CI `prod-smoke`가 검증한다(로컬 `prod-smoke.sh`는 폐기 가능한 Docker 환경 전용이라 돌리지 않는다).
- PR 직전: CLAUDE.md 4항대로 `./gradlew test -PbrowserTests=exclude`를 백그라운드로 1회 통과시킨다(`code-review-loop` 마무리). 브라우저 시험은 화면 변경이 없어 PR CI `browser-test`에 맡긴다.

## 6. 실패 모드와 고정 수단
| # | 실패 모드 | 고정 수단 |
|---|---|---|
| F1 | #271 머지 전 master에서 분기해 옛 digest + `RUN` 없음 상태로 머지 | 일차: 브랜치 생성 직후 런타임 `FROM`의 digest 전체(64자)가 §2 값과 같은지 비교 + S2 이미지 안 설치 버전 확인. 이차: PR CI Trivy(DB가 이 CVE를 수정판 있음으로 수록하는 한 실패) |
| F2 | 같은 digest의 다른 플랫폼(arm64) 매니페스트는 미검증 | 운영 플랫폼 미정(#238), compose `build: .`에 플랫폼 제한 없음. 이번 검증은 linux/amd64뿐임을 결과 문서에 명시하고 "다른 플랫폼 배포 전 별도 검증"을 후속에 기록 |
| F3 | `RUN`이 의존성 때문에 다른 패키지도 상향했다(지우면 그 상향이 사라짐) | `--only-upgrade`는 이를 보장하지 않는다. 대신 실측으로 고정: 새 digest에서 `RUN` 전후 패키지 목록 동일(§2). S2 확인 시 제거 이미지의 `dpkg-query -W`를 한 번 더 저장해 base 목록과 비교 |
| F4 | 문서에 사라진 `RUN`을 "현재"로 안내하는 문장이 남음 | `grep -rn "only-upgrade\|libapt-pkg" docs Dockerfile CLAUDE.md`가 이력 문장 외 0건 |
| F5 | 제거 PR 머지 뒤 #271만 되돌림(옛 digest + `RUN` 없음 = 취약) | 되돌리기 규칙을 `docs/deployment.md` 괄호 문장에 함께 적는다: "베이스 digest를 옛 값으로 되돌릴 때는 제거 PR부터 되돌리거나 상향 `RUN`을 함께 복원하고 이미지를 다시 확인한다" |

## 7. 위험 변경
없음. 인증·인가·데이터·잠금·스키마·보안 설정은 건드리지 않는다. 의존성 변경은 베이스 이미지 digest(#271, Dependabot)뿐이고, 이 PR은 빌드 단계 하나를 지운다.

---

# 구현·검증 결과 (2026-11-17)

## Context
#271(`4640d69`, 런타임 digest `0d57a8e9…2515`)이 master에 머지된 뒤 `chore/remove-apt-upgrade-run`에서 작업했다. 같은 시기 Dependabot #272·#273 머지, #270(mariadb digest)은 스크립트·시험 리터럴 5곳을 맞추는 커밋 `abac112`를 그 PR 브랜치에 push해 별도 처리(이 계획 범위 밖).

## 핵심 확정 사항
- 계획 v3 그대로 구현. 계획과 달라진 결정 없음.
- `docs/deployment.md` 괄호는 계획 §3 문안을 다듬어, 확인 명령(`docker run … dpkg-query -W`)과 되돌리기 규칙(F5)을 본문으로 올리고 이번 건은 "사례"로 남겼다.

## 구현 파일
- `Dockerfile`: 런타임 단계의 CVE-2026-109518 주석 2줄·`RUN` 3줄·빈 줄 1개 제거
- `docs/deployment.md` "스캔 실패 시 절차": 현재 적용 중 안내 → 확인 명령·되돌리기 규칙·사례 이력

## 검증 결과
| 기준 | 결과 | 근거 |
|---|---|---|
| S1 | 충족 — 런타임 `FROM` digest 64자 전체 일치, `grep -c apt-get Dockerfile` = 0 | 브랜치 생성 직후·수정 후 실측 |
| S2 | 충족 — 로컬 `docker build`(이미지 `sha256:663b0fc0…`) 안 `apt`·`libapt-pkg6.0t64:amd64` = `2.8.4ubuntu0.1`, `dpkg-query -W` 136줄이 베이스 digest와 `diff` 동일, `appuser` uid/gid 10001·`/app/data/attachments` 소유 `appuser` 유지 | 실측(linux/amd64, x86_64) |
| F4 | 충족 — `only-upgrade`·`libapt-pkg` 언급은 `docs/deployment.md` 사례 이력 1곳뿐 | grep(docs·Dockerfile·CLAUDE.md·src/main) |
| 이미지 참조 검사 | 통과 — `scripts/ci/check-image-refs.sh` | 로컬 실행 |
| S3·S4 | PR CI 대기 — Trivy(v0.70.0, `ignore-unfixed: true`) 통과는 "그 시점 DB 기준 수정판 있는 HIGH/CRITICAL 없음"을 뜻한다 | — |
| PR 직전 전체 시험 | `code-review-loop` 마무리에서 `-PbrowserTests=exclude` 1회 | — |

## 이슈
- 없음(로컬 빌드·비교 1회 통과).

## 후속
- arm64 등 다른 플랫폼 매니페스트의 `apt` 버전은 미검증(F2) — 운영 플랫폼이 정해지면(#238) 그 플랫폼 이미지로 확인한다.
