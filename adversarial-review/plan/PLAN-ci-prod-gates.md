# PLAN — CI 배포 게이트 확장 (로드맵 "우선순위에서 밀린 감사 항목" M-06)

> 개정 이력
> - v1 (2026-09-29): 최초 작성 (정찰 결과 + 설계 결정).
> - v2 (2026-09-29, codex 1라운드 needs-attention 6건 전부 수용 — 기각 없음):
>   1. 수용: 로컬 재현 스크립트가 고정 이름 prod 컨테이너·볼륨(`cms-*-prod`, `cms_*_prod`)·`.env.prod`를 건드릴 수 있음 → 격리 전제·preflight 중단·자기 생성 자원만 정리(쟁점 1 "격리").
>   2. 수용: Swagger 경로는 `SecurityConfig`에서 `hasRole("ADMIN")`이라 무인증 404 검증이 성립하지 않음(코드로 확인) → 합성 ADMIN 로그인 후 검증.
>   3. 수용: "200이 아님"은 5xx·연결 실패(000)를 통과시킴 → 허용 응답 명시 + 열거한 대표 경로로 완료 기준 한정.
>   4. 수용: `prod-backup.sh:113`·`prod-restore.sh:166,181`도 `mariadb:10.11` 하드코딩(코드로 확인, 정찰 누락) → 스크립트도 고정 + 참조 일치 검사(쟁점 4). "스크립트 본문 무수정" 제약을 이미지 참조 리터럴 치환 한정으로 조정.
>   5. 수용: 기준선 초과 시 `exit-code: 0`으로 낮추면서 완료를 주장하는 모순 → "검사 추가"와 "게이트 활성"을 분리하고 예외는 `.trivyignore.yaml`(`expired_at`)로 만료 검증(쟁점 3·완료 기준).
>   6. 수용: 왕복·음성 테스트 관측 강화(쟁점 2).
> - v3 (2026-09-29, codex 2라운드 needs-attention 1건 수용): 테스트 컨테이너 "태그 유지" 대안이 `check-image-refs.sh` 전 참조 일치 검사·완료 기준과 모순 → 대안 삭제, digest 호환성 해결을 완료 조건으로(실패 시 미완료 보고·사용자 협의).

## Context

로드맵 M-06: "CI가 prod 이미지 기동·백업복구·의존성 스캔을 검증하지 않음, Dockerfile/Compose가 가변 태그(`mariadb:10.11`) 사용".

정찰로 확인한 현재 사실(추측 없음):

- `.github/workflows/ci.yml`은 job 1개(`test`): `./gradlew test`만 실행. 브랜치 보호의 필수 체크는 `test` 하나(`docs/branching.md`).
- prod 이미지는 어떤 자동 경로로도 빌드·기동되지 않는다. 검증은 사람이 `make prod-up`으로만 수행(`scripts/prod-up.sh`: `docker compose up -d --build` → health 200 → 3초 후 RestartCount·health 재확인).
- 백업/복구 스크립트(`prod-backup.sh` 143줄, `prod-restore.sh` 229줄)는 실기 drill(`docs/verification/recovery-drill.md`)로만 검증됐고, 쉘 단위 테스트는 `scripts/tests/quiesced-runbook-test.sh`(docker 스텁)뿐이다. CI에서 도는 것은 없다. `prod-restore.sh`는 대화형(`read -r -p`로 DB 이름 입력, 우회 플래그 없음 — 의도된 설계)이다.
- 가변 태그: `mariadb:10.11`이 `docker-compose.dev.yml:10`·`docker-compose.prod.yml:9`·`src/test/java/com/cms/support/MariaDbContainerSupport.java:31` 3곳, `eclipse-temurin:17-jdk`/`17-jre`가 `Dockerfile` 2곳. digest 고정 없음.
- 의존성 스캔 없음. `.github/dependabot.yml` 없음. Gradle lockfile 없음(`gradle.lockfile` 부재).
- 스키마·인가 정책·앱 코드 변경 없음(워크플로·스크립트·설정·문서만).

## 핵심 설계 쟁점과 결정

### 쟁점 1 — prod 기동 검증 방식
- A) 별도 job에서 `scripts/prod-up.sh`를 그대로 실행(합성 `.env.prod` 생성)
- B) 별도 워크플로로 분리
- C) 기존 `test` job에 단계 추가
- **결정: A.** 운영자가 실제로 쓰는 스크립트와 동일 경로를 검증해야 게이트의 의미가 있다(스크립트 자체의 회귀도 잡는다). 신규 job `prod-smoke`로 두어 `test`(필수 체크)의 시간·안정성에 영향을 주지 않는다. 합성 `.env.prod`의 값은 워크플로에 인라인(실 시크릿 아님), 러너 임시 파일이며 `.gitignore`(`.env*`) 대상. 초기 관리자 부트스트랩 3변수를 채워 빈 DB 첫 기동 경로(`AdminBootstrapLoader`)까지 검증한다. 종료 시 `if: always()`로 로그 출력 + `prod-down.sh`.
- **격리(v2)**: compose 프로젝트명·컨테이너명·볼륨명·`.env.prod`가 전부 고정 이름이라 다른 디렉터리에서도 격리되지 않는다. 따라서 `scripts/ci/*.sh`는 **폐기 가능한 Docker 환경(CI 러너 또는 사용자가 확인한 전용 환경)에서만** 실행하며, 시작 시 preflight로 (a) `.env.prod` 존재 (b) `cms-app-prod`/`cms-db-prod` 컨테이너 존재(정지 포함) (c) `cms_db_data_prod`/`cms_notice_attachments_prod` 볼륨 존재 (d) 127.0.0.1:8080 점유 중 하나라도 해당하면 **어떤 변경도 하기 전에 중단**한다. CI 러너는 항상 깨끗하다. 로컬 실행은 `CMS_CI_DISPOSABLE_DOCKER=1`을 명시해야 시작한다(오실행 방지 확인 장치). 정리(trap)는 preflight 통과 후 **이번 실행이 만든 자원**(합성 `.env.prod`, 위 컨테이너·볼륨, 임시 백업 디렉터리)만 대상으로 하며 preflight 중단 시에는 아무것도 지우지 않는다. `prod-down.sh`는 볼륨을 보존하므로 정리 단계에서 고정 볼륨 2개를 명시 삭제한다(preflight가 사전 부재를 증명했으므로 안전) — 재실행도 "빈 DB 첫 기동"이 보장된다.
- 검증 범위(스모크, v2 — 열거 항목으로만 한정): 이미지 빌드 성공 → 기동 → `/actuator/health` 200 → RestartCount 안정 → `GET /admin/login` 200.
  - **Actuator 비공개**: 무인증으로 `/actuator/env`·`/actuator/beans`·`/actuator/metrics`(대표 3경로)를 요청해 **302이고 `Location`이 `/admin/login`을 가리킬 때만** 통과(`CLAUDE.md`: 기본 거부 시 비인증 302). curl 전송 실패(`000`)·5xx·200은 실패. 합성 ADMIN 로그인 후 같은 3경로는 **403**만 통과. "`/actuator/**` 전체"가 아니라 이 3경로를 증명한다고 문서·완료 기준에 한정해 적는다.
  - **Swagger 비활성(prod)**: Swagger 경로는 `hasRole("ADMIN")`이라 무인증 검증은 인증 처리에 먼저 걸린다. 합성 ADMIN(`ADMIN_BOOTSTRAP_*`)으로 폼 로그인(쿠키 jar + `_csrf` 파싱)한 뒤 `/swagger-ui.html`과 `/v3/api-docs`가 **404**임을 확인한다. 로그인 자동화가 구현 단계에서 불안정하면 계획 변경으로 보고하고 이 항목만 제외한다(무인증 리다이렉트로 대체하지 않는다 — 비활성화 증명이 아님).

### 쟁점 2 — 백업·복구 CI 검증 범위
- A) 실제 `prod-backup.sh` → 변경 → `prod-restore.sh` 왕복을 CI에서 자동화(합성 DB 마커 행으로 확인)
- B) 백업 성공(무결성 검증 통과)까지만
- C) 미포함(수동 drill 유지)
- **결정: A의 축소판(DB 마커 왕복 + 파일 볼륨 마커 왕복 1개).** 복구가 가장 사고 비용이 큰 경로인데 CI에서 한 번도 안 도는 것이 M-06의 핵심 공백이다. `prod-restore.sh`의 대화형 확인은 우회하지 않고 **표준입력으로 DB 이름을 파이프**한다(스크립트 무수정, 확인 관문은 그대로 통과 — "잘못된 이름 입력 시 중단"도 1건 음성 케이스로 검증). 앱 API를 통한 fixture 생성은 CSRF·로그인 플로우로 취약해지므로 쓰지 않고, DB는 `docker exec ... mariadb`로 마커 테이블/행 조작, 파일은 `docker run`으로 볼륨에 마커 파일을 쓰고 sha256 대조한다. 첨부 저장 규약과 어긋나는 임의 파일은 앱 조회 검증 대상이 아니며(앱 레벨 조회는 수동 drill 문서의 범위 유지) CI는 "바이트·UID/GID·DB 행 복원"까지만 증명한다.
- **관측 강화(v2)**: (1) 백업 직후 마커 기록(DB 행 값, 볼륨 파일 sha256·`10001:10001` 소유권) → (2) 변경 후 **변경이 실제 반영됐음을 복구 직전에 단언**(DB 값·파일 내용이 백업 시점과 다름) → (3) 백업 이후 추가한 행·파일도 만들어 둔다 → (4) 복구 후 원본 값·해시 복원 **및** 백업 이후 추가분 소멸, 볼륨 파일 집합이 백업 시점 집합과 정확히 동일 → (5) 소유권 `10001:10001`(root 기본값 아님) 단언 → (6) 앱 재기동·health·RestartCount 안정.
- **음성 케이스(v2)**: 잘못된 DB 이름 입력 시 (a) 종료 코드 비정상 **그리고** (b) 출력에 입력 불일치 거절 문구(스크립트의 실제 문구를 구현 시 확인해 그 문자열로 단언 — 체크섬·잠금 실패로는 통과 불가) (c) 거절 후 DB 행·볼륨 파일·앱 컨테이너 `Running=true`가 불변.
- 한계 명시: CI 왕복은 drill(`recovery-drill.md`)을 대체하지 않는다.

### 쟁점 3 — 의존성·이미지 스캔 도구
- A) Trivy(`aquasecurity/trivy-action`)로 **빌드된 이미지**를 스캔 — OS 레이어 + fat jar 내부 라이브러리(Trivy가 jar 내 `pom.properties`로 식별) 모두 커버
- B) OWASP dependency-check Gradle 플러그인 — 신규 빌드 의존성, NVD 느림·키 필요
- C) Dependabot(알림/PR만) — 스캔 게이트는 아님
- **결정: A(이미지 스캔, 게이트) + C(Dependabot 갱신 PR).** lockfile이 없어 `trivy fs`로는 Gradle 의존성이 해석되지 않으므로 이미지 스캔이 유일하게 앱 라이브러리까지 보는 저비용 방법이다(프로젝트 빌드에 의존성 추가 없음 — 프로젝트 규칙 "새 의존성은 먼저 제안" 위반 아님, 다만 CI 툴 추가이므로 승인 시 고지). Dependabot은 gradle·docker·github-actions 3 생태계.
- 게이트 정책: `severity: CRITICAL,HIGH`, `ignore-unfixed: true`, `exit-code: 1`. 수정판이 존재하는 HIGH 이상만 차단해 "고칠 수 없는 것 때문에 빨간불"을 피한다. 예외는 주석이 아니라 **`.trivyignore.yaml`의 `statement`(사유·책임자)·`expired_at`(만료일)·`paths`(적용 범위)**로 기록한다 — Trivy가 만료된 예외를 자동 해제하므로 만료 후 다시 실패해 예외가 영구화되지 않는다(구현 시 사용 Trivy 버전의 필드 지원을 실측, 미지원이면 예외 파일 검사 스크립트로 대체).
- **초기 기준선 측정을 구현 단계 첫 작업으로 한다**: 도입 시점에 이미 걸리는 항목이 있으면 (1) 가능하면 버전 상향 (2) 아니면 만료일 있는 예외로 기록. 예외가 다수(임의 기준 5건 초과)여서 게이트를 켤 수 없으면 스캔을 **보고 전용**으로 두되, 이 경우 M-06은 **"검사 추가·보고 전용" 부분 완료로만 보고**하고 게이트 완료를 주장하지 않는다(완료 기준의 스캔 게이트 항목 미충족 명시).
- "검사 추가"와 "머지 차단 게이트 활성"은 분리한다: 후자는 브랜치 보호에 `prod-smoke`를 필수 체크로 등록해야 성립하는 사용자 설정 작업이다. 완료 보고에 두 상태를 각각 적는다.
- 외부 액션은 커밋 SHA로 고정(태그 이동 공격 방어). Trivy DB 다운로드 실패는 job 실패가 아닌 재시도 후 실패로 다룬다(캐시 미사용, 단순화).

### 쟁점 4 — 가변 태그 고정
- A) digest 고정(`image:tag@sha256:...`) + Dependabot docker 생태계로 자동 갱신 PR
- B) 정확한 패치 태그(`10.11.14`)만 고정
- C) 현행 유지
- **결정: A.** 태그는 재푸시로 바뀔 수 있어 "배포 가능한 master"의 재현성을 깨고, digest 고정만이 이를 막는다. digest 고정의 단점(자동 보안 패치 정지)은 Dependabot이 상쇄한다. 대상: `Dockerfile` 2곳(`eclipse-temurin:17-jdk`·`17-jre`), `docker-compose.prod.yml`·`docker-compose.dev.yml`의 `mariadb`. **v2 추가 대상**: `scripts/prod-backup.sh:113`·`scripts/prod-restore.sh:166,181`의 `docker run ... mariadb:10.11`(복구용 컨테이너는 파일 볼륨 쓰기 권한 보유)과 `prod-backup.sh:119`의 manifest `db_image=` 기록. 이 스크립트들은 "본문 무수정" 제약의 예외로 **이미지 참조 리터럴 치환만** 허용한다(로직 변경 없음). `db_image=`가 복구 시 파싱·비교되는지 구현 시 확인하고, 비교된다면 이전 백업과의 호환을 계획 변경으로 보고한다(현 grep상 복구 로직의 파싱 흔적은 없음). 정찰 정정: 가변 참조는 총 8곳(Dockerfile 2 + compose 2 + 테스트 1 + 스크립트 3).
- **일치 검사(v2)**: `scripts/ci/check-image-refs.sh`가 모든 `mariadb` 참조가 동일 `image:tag@sha256:...`인지 grep으로 대조하고 `prod-smoke` 첫 단계에서 실행한다 — Dependabot이 compose만 갱신하고 스크립트·테스트 리터럴이 남는 drift를 CI가 잡는다.
- digest는 구현 시점에 `docker buildx imagetools inspect`로 조회해 기록한다(계획 단계에서 값을 박지 않는다).
- 테스트 컨테이너 `MariaDbContainerSupport`(`mariadb:10.11` 리터럴)는 **동일 digest로 맞추되**, `DockerImageName.parse("mariadb:10.11@sha256:...")` + Testcontainers 호환성(`asCompatibleSubstituteFor`)을 구현 단계에서 실측한다. **호환성 해결은 완료 조건이다(v3)** — 태그 유지 예외는 두지 않는다(`check-image-refs.sh`의 "전 참조 동일" 검사와 모순됨). 실측에서 digest 참조가 동작하지 않으면 우회하지 말고 해당 항목을 **미완료로 보고**하고 계획 변경을 사용자와 협의한다. Dependabot은 Java 문자열 리터럴을 추적하지 못하므로 이 한 곳은 수동 갱신 대상임을 `docs/deployment.md`에 명시한다.
- mariadb `healthcheck.sh` 존재 재확인(로드맵 후속 과제의 "미확인" 항목)은 스모크가 기동 성공으로 자동 커버한다.

### 쟁점 5 — 실행 시점·비용·필수 체크
- `prod-smoke`(빌드+기동+백업복구+스캔): PR·master push 모두 실행. 이미지 빌드가 Gradle 전체 빌드를 포함해 수 분 소요. 경로 필터로 줄이면 Dependabot·문서 PR에서 게이트가 우회되는 함정이 있어 **경로 필터 미사용**(단순성 우선). 두 job(`test`, `prod-smoke`)은 병렬.
- 필수 체크(branch protection)에 `prod-smoke` 추가는 **저장소 설정이라 코드로 못 한다** → 승인 후 사용자가 GitHub 설정에서 직접 추가하도록 안내만 한다(계획 범위 밖).
- 스캔은 `prod-smoke` job의 마지막 단계(이미 빌드된 이미지 재사용). 스캔과 스모크를 분리 job으로 나누면 이미지를 두 번 빌드하거나 artifact 전달이 필요해 과설계.

## 설계 제약(명문화)

- 애플리케이션 코드·Flyway·인가 정책은 수정하지 않는다. `prod-*.sh`는 이미지 참조 리터럴 치환 외 수정하지 않는다(필요하면 계획 변경으로 보고). CI가 스크립트를 있는 그대로 부르는 것이 목적이다.
- 합성 시크릿은 워크플로 상수로만 존재하며 실 환경과 무관함을 주석으로 명시한다.
- 새 러너 전용 스크립트가 필요하면 `scripts/ci/`에 두고 로컬 재현 가능해야 한다(`bash scripts/ci/prod-smoke.sh`, Docker만 있으면 실행). 워크플로 YAML에 긴 쉘을 인라인하지 않는다 — 로컬에서 같은 검증을 돌려 실기 확인할 수 있어야 하기 때문(쟁점 1·2의 스텝 본문을 스크립트로 이동).

## 수정·신규 파일

- 신규: `scripts/ci/prod-smoke.sh`(합성 env 생성·`prod-up.sh` 호출·스모크 curl 검증·정리), `scripts/ci/prod-backup-restore-roundtrip.sh`(왕복 + 음성 케이스)
- 수정: `.github/workflows/ci.yml`(job `prod-smoke` 추가, 액션 SHA 고정)
- 신규: `.github/dependabot.yml`, `.trivyignore.yaml`, `scripts/ci/check-image-refs.sh`
- 수정: `Dockerfile`·`docker-compose.prod.yml`·`docker-compose.dev.yml`(digest 고정), `MariaDbContainerSupport.java`(조건부), `scripts/prod-backup.sh`·`scripts/prod-restore.sh`(이미지 참조 리터럴 치환만)
- 수정 문서: `docs/deployment.md`(digest 갱신 절차·CI 게이트 설명), `CLAUDE.md`(필요 시 한 줄), `docs/branching.md`(필수 체크 추가 안내), 로드맵 M-06 완료 반영은 `/updateRoadmap` 몫
- 수정 없음: 앱 코드, `scripts/prod-*.sh`, 마이그레이션

## 단계별 작업 순서

1. 브랜치 `chore/ci-prod-gates`
2. 기준선 측정: 현재 이미지로 Trivy 로컬 스캔(CRITICAL/HIGH, fixed) → 결과 기록·정책 확정
3. digest 조회·고정(Dockerfile·compose 2종), 테스트 컨테이너 호환 실측, 전체 `./gradlew test`
4. `scripts/ci/prod-smoke.sh` 로컬 실행으로 골든 패스 확인 → 의도적 실패 주입(잘못된 env 등)으로 게이트가 빨개지는지 확인
5. `scripts/ci/prod-backup-restore-roundtrip.sh` 로컬 실행(정상 왕복 + 잘못된 DB 이름 입력 거절)
6. `ci.yml` job 추가 + `dependabot.yml` + `.trivyignore`
7. PR에서 실제 CI로 job 통과 확인, 실패 주입 PR 커밋 1개로 게이트 동작 확인 후 되돌림
8. 문서 갱신

## 완료 기준

- 스크립트 preflight: 기존 `.env.prod`·`cms-*-prod` 컨테이너·`cms_*_prod` 볼륨·8080 점유 중 하나라도 있으면 아무 변경 없이 중단함을 각각 확인(로컬 실기). 정상 실행 후 자기 생성 자원만 정리됨.
- PR CI에서 `prod-smoke` 통과 시 자동 확인되는 것: 이미지 빌드, health 200, RestartCount 안정, `/admin/login` 200, 대표 3경로(`/actuator/env`·`beans`·`metrics`)가 무인증 302→`/admin/login`·ADMIN 403, ADMIN 로그인 후 `/swagger-ui.html`·`/v3/api-docs` 404. (`/actuator/**` 전체·기타 경로는 증명 범위 밖.)
- 백업→변경→복구 왕복: 복구 직전 변경 반영 단언, 복구 후 원본 값·sha256 복원, 백업 이후 추가 행·파일 소멸, 파일 집합 동일, 소유권 `10001:10001`, 앱 안정. 음성 케이스: 입력 불일치 사유의 거절 + DB·파일·앱 상태 불변.
- 이미지 참조 8곳 digest 고정 + `check-image-refs.sh` 일치 검증(일부러 하나를 어긋나게 해 실패함을 확인). Dependabot 설정 존재.
- 스캔: 수정판 있는 HIGH/CRITICAL이 있으면 `prod-smoke`가 실패함을 임시 주입으로 확인, 예외는 만료일 있는 `.trivyignore.yaml`로만 존재. 기준선 초과로 보고 전용이 되면 게이트 항목은 **미충족**으로 명시 보고.
- 기존 `test` job 결과·소요시간 무영향, `./gradlew test` 전체 통과.
- **별도 상태 표기**: "검사 추가 완료"와 "머지 차단 게이트 활성(필수 체크 등록 — 사용자 설정)"을 분리해 보고.

## 리스크

- 외부 요인(Trivy DB·Docker Hub 레이트리밋·GitHub 러너)으로 인한 플레이키 실패 → 필수 체크 지정은 사용자가 안정성 확인 후 결정(계획은 미지정), Trivy DB 다운로드는 1회 재시도.
- 스캔 게이트가 무관한 PR을 막을 수 있음(새 CVE 공개 시) → `ignore-unfixed` + `.trivyignore` 만료일 운용, 급할 때 우회 절차는 `docs/deployment.md`에 기록.
- CI 시간 증가(예상 5~8분, 병렬이라 `test` 대기는 그대로) — 실측 후 기록.
- digest 고정은 Dependabot PR을 리뷰·머지하는 운용 부담을 만든다.
- 합성 `.env.prod` 경로가 러너에서 `.dockerignore`(`.env*`)로 빌드 컨텍스트에서 제외됨을 이미 확인(시크릿이 이미지에 안 들어감) — 스모크에서도 유지.

## 구현·검증 결과 (2026-09-29 완료 — PR #47 `ed193ed` 머지, 실제 CI 통과 확인, 필수 체크 `prod-smoke` 등록까지 확인)

### Context
계획 v3 승인(2026-09-29) 후 `chore/ci-prod-gates` 브랜치에서 구현. 스키마·인가·앱 코드 변경 없음.

### 핵심 확정 사항 (계획 대비 변경 포함)
- **digest 표기 변경(계획과 다름)**: Testcontainers `MariaDBContainer`가 `mariadb:10.11@sha256:...`(tag+digest)를 호환 이미지로 인식하지 못하고(`asCompatibleSubstituteFor`를 붙이면 Spring Boot ServiceConnection의 이름 검증에서 `IllegalArgumentException`), digest 단독 형식 `mariadb@sha256:...`만 동작함을 실측했다. 그래서 테스트 리터럴만 태그 없는 형식을 쓰고 `check-image-refs.sh`는 태그가 아닌 **sha256 값**으로 전 참조 일치를 검사한다(계획 v3의 "전 참조 동일" 원칙 유지, 예외 아님).
- **Trivy 기준선 결과가 계획의 분기를 탔다**: 수정판 있는 HIGH/CRITICAL이 OS 레이어 0건, `app/app.jar` 4건(Tomcat 10.1.55 CRITICAL 3·jackson-databind 2.21.4 HIGH 1)이었다. 사용자 결정으로 예외 처리·보고 전용 대신 **별도 브랜치(`security/bump-tomcat-jackson`)에서 BOM 속성 오버라이드로 선행 상향**(Tomcat 10.1.60·Jackson 2.21.7, 10.1.58은 Maven Central 미배포)한 뒤 이 PR은 예외 0건으로 게이트를 켠다. 상향 브랜치에서 전체 `./gradlew test` 통과·재빌드 이미지 Trivy 0건·codex 리뷰 approve 확인. **이 PR은 그 PR 머지 후 rebase가 필요하다**(머지 전에는 `prod-smoke`의 스캔 단계가 실패한다).
- Trivy 기본 타임아웃(5m)이 Java DB 다운로드만으로 초과해(로컬 실측) `timeout: 20m` 지정.
- 스크립트 구조: `scripts/ci/_prod-ci-common.sh`(preflight·합성 env·정리·helper 공용) + `prod-smoke.sh`(스모크 후 같은 스택에서 왕복 스크립트 호출) + `prod-backup-restore-roundtrip.sh`(단독 실행도 가능) + `check-image-refs.sh`.

### 구현 파일
- 신규: `scripts/ci/_prod-ci-common.sh`·`prod-smoke.sh`·`prod-backup-restore-roundtrip.sh`·`check-image-refs.sh`, `.github/dependabot.yml`, `.trivyignore.yaml`(예외 0건)
- 수정: `.github/workflows/ci.yml`(job `prod-smoke`, Trivy 액션 커밋 SHA `ed142fd…`=v0.36.0 고정), `Dockerfile`·`docker-compose.dev.yml`·`docker-compose.prod.yml`(digest), `scripts/prod-backup.sh`·`prod-restore.sh`(이미지 참조 리터럴만 — 로직 무변경), `MariaDbContainerSupport.java`, `docs/deployment.md`·`docs/branching.md`

### 검증 결과 (로컬, Docker Desktop, dev 앱 임시 정지 후)
- `check-image-refs.sh`: 통과 / digest 1곳 변조 → 실패 / compose 가변 태그 복귀 → 실패(각각 확인 후 복원).
- Testcontainers digest 호환: `ProfileImageMigrationRunnerIntegrationTest` 통과(tag@digest 2가지 시도 실패 → digest 단독 성공).
- `CMS_CI_DISPOSABLE_DOCKER=1 bash scripts/ci/prod-smoke.sh` 전 구간 통과: 이미지 빌드·기동, health 200, `/admin/login` 200, 무인증 Actuator 3경로 302→`/admin/login`, 합성 ADMIN 로그인 후 3경로 403·`/swagger-ui.html`·`/v3/api-docs` 404, 백업→변경 반영 단언→잘못된 DB 이름 거절(입력 불일치 문구·상태 불변)→실제 복구→행·해시·소유권 복원·추가분 소멸. 종료 후 prod 컨테이너·볼륨·`.env.prod` 잔존 없음.
- preflight 음성 5건(확인장치 없음·포트 8080 점유·기존 `.env.prod`(내용 무손상 확인)·기존 컨테이너·기존 볼륨) 전부 아무 변경 없이 중단.
- Trivy 로컬 스캔: 기준선 4건 → 상향 브랜치 이미지 0건.

### 코드 리뷰 루프 (codex 3라운드: needs-attention → needs-attention → approve)
- 1라운드 수용 2건: (a) `CMS_CI_STACK_UP=1`+`CMS_CI_TMP_DIR`만으로 모든 안전 검사를 우회해 기존 `cms-db-prod`에 쓸 수 있음 → 재사용 진입 시 부모 실행 토큰(잠금 라벨)·합성 DB 이름을 쓰기 전에 검증(`ci_require_parent_ownership`). (b) preflight 통과를 소유권으로 간주해 동시 실행 시 타 실행 스택을 `down -v`로 지울 수 있음 → 배타 잠금 도입, trap은 잠금 획득 후·소유권은 preflight 통과 후. 기각: "생성 자원 ID 기록" 제안(배타 잠금+고정 이름 preflight로 충분, 복잡도만 증가).
- 2라운드 수용 2건: (c) `docker network create` 이름 중복 검사는 Docker 문서상 원자적 보장이 없음(내 1라운드 수정의 잘못된 가정) → 이름 유일성을 데몬이 예약 시점에 강제하는 **잠금 컨테이너**(`cms-ci-prod-lock`, 기동 없이 create만)로 교체, SIGKILL 잔존 시 자동 탈취 없이 수동 `docker rm` 안내. (d) 부모 셸의 prod 환경변수(예: 빈 `MYSQL_PASSWORD`)가 cleanup의 compose 보간을 깨 스택·`.env.prod`가 남음 → 부모 셸에도 `_prod-env-guard.sh` 적용, `down -v` 실패를 종료 코드에 반영하고 실패 시 `.env.prod`·잠금 보존.
- 3라운드: approve, 지적 0건.
- 수정 후 로컬 확인: 위조 재사용 환경변수·확인장치 없음·잠금 선점·위조 토큰 음성 4건 전부 쓰기 전 중단, 2 프로세스 동시 잠금 획득에서 1개만 성공·잔존 잠금 0, 빈 `MYSQL_PASSWORD` export 상태 전체 스모크 통과(가드 경고 출력, 종료 후 잔존 자원 없음).
- 이 브랜치(bump 머지 후 rebase) 전체 `./gradlew test` 790개 통과(스킵 1: 기존 Windows symlink 테스트), 스모크·Trivy(상향 의존성 이미지) 0건 재확인.

### 이슈·미확인
- ~~실제 GitHub Actions 러너에서의 `prod-smoke` 통과 미확인~~ → **해소(2026-09-29)**: PR #47의 CI에서 `prod-smoke` pass(2m24s)·`test` pass(1m38s) 확인(`gh pr checks 47`), 머지 커밋 `ed193ed`.
- **참고(범위 밖)**: `core.autocrlf=true`인 Windows 작업 트리에서는 브랜치 전환 시 `scripts/*.sh`가 CRLF로 체크아웃돼 로컬 `bash scripts/...`가 실패한다(기존 `prod-up.sh` 포함, 인덱스는 LF라 리눅스 CI 무관). `.gitattributes`에 `*.sh text eol=lf` 추가를 후속 후보로 남긴다.
- 스캔 실패 주입(임시 낮은 임계값) 확인은 CI 상에서 미실시(로컬 Trivy가 4건을 실제로 검출·`exit=1`한 것으로 게이트 동작은 확인).
- 스모크 자체의 실패 주입(예: Actuator 응답 변조)은 수행하지 않았다.
- ~~필수 체크 등록은 사용자 설정 작업 — "검사 추가"는 완료, "머지 차단 게이트 활성"은 미완료(브랜치 보호 필수 체크 `["test"]`만 등록)~~ → **해소(2026-09-29)**: 사용자가 GitHub 설정에서 등록했고 `gh api .../branches/master/protection/required_status_checks`로 `["test","prod-smoke"]`(둘 다 app_id 15368)를 확인했다. "검사 추가"와 "머지 차단 게이트 활성" 두 상태가 모두 완료됐다.

### 후속
- `security/bump-tomcat-jackson` PR 머지 → 이 브랜치 rebase → 실제 CI 확인 → `prod-smoke`를 브랜치 보호 필수 체크로 등록(사용자, 2026-09-29 완료·확인) → `/updateRoadmap`(14차 반영 완료).

## 범위 밖(명시)

CD·실배포, 오프사이트 백업, SBOM/서명, 필수 체크 지정(저장소 설정), 앱 레벨 첨부 조회 검증(수동 drill 유지), M-04(health 판정 개선)·M-05(Clock 통일).
