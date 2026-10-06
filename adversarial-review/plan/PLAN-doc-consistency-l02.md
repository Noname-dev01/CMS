# PLAN: 문서-코드 정합 (감사 L-02)

- 작성: 2026-10-06
- 브랜치(예정): `chore/doc-consistency-l02` (base `master`)
- 상태: v9 (계획 리뷰 7라운드 + 사용자 결정 U3 반영) — **승인(2026-10-06)** · **구현·검증 완료(2026-10-06, 커밋·PR 전)**

## 구현·검증 결과 (2026-10-06)

### Context
감사 L-02(문서-코드 불일치)를 정찰에서 확인한 F1~F11·G1~G3 기준으로 정리했다. 문서만 변경 — 코드·설정·스키마·인가 정책·의존성 변경 없음.

### 핵심 확정 사항
- 사용자 결정: U1 실행 절차 누락(`.env.dev`·Swagger 조건) 포함, U2 주요 기능 보강, U3 `.env.dev` 형식 규칙을 "영문·숫자·`_`·`-`만" 한 줄로 축소(3~7라운드 형식 지적 7건의 실패 조건을 문자 집합 제한으로 일괄 제거).
- 이력 보존(D1): `troubleshooting.md`·`docs/plans`·실증 기록·날짜 붙은 결정 절은 수정하지 않음. `dependabot.md` 첫 실행 표는 행을 고치지 않고 아래에 후속 상태를 덧붙임.
- **계획과 달라진 점 1**: V3 실기 앱 포트를 8080 → **8091**(`SERVER_PORT`)로 — 8080을 사용자의 실행 중인 dev 스택(`cms-app-dev`)이 쓰고 있어, 사용자 스택을 멈추지 않는 쪽을 택했다(계획 §6 "사용 중이면 다른 포트로 바꾸고 기록").
- **계획과 달라진 점 2**: `dependabot.md` 후속 상태 문장을 쓰는 중 "wrapper는 이후 8.x 마이너 갱신"이라는 초안이 근거 없는 서술임을 확인 → `git log`로 #60(8.12.1→8.14.5, 2026-09-29)을 찾아 그 사실로 고쳤다.

### 구현 파일
- `README.md`: 배지(Boot 4.0·Security 7·Hibernate 7), 주요 기능(콘텐츠(공지사항)·위임 권한 관리·상단바 알림·쪽지 추가, 파일 스토리지 서술 정정), Quick Start 5️⃣ `.env.dev` 작성(키 목록·값 형식·계정/DB 이름 일치·기존 볼륨·셸 변수 우선·근거 설정), IntelliJ 환경변수 주입, 접속 안내, Security 위임 권한 한 줄, API Documentation 접근 조건.
- `docs/deployment.md`: ingress 절 IP 소스 서술을 #44 이후 상태로(F4), BOM 오버라이드 3개(F7), 오프사이트 백업 "기록됨"(F6).
- `docs/verification/deployment-edge.md`: 체크리스트 7번째 항목 서술 갱신 + 갱신 이력 1행(F5).
- `docs/migration-guide.md`: 표 V13~V22 한 행씩·버전 순, V20·V21 추가(F8). baseline 절차 — 대상 DB 백업 선행·대상 일치·`make prod-backup` prod 전용·셸 환경변수 전제·V2~최대 버전 적용·확인 기대값(F11).
- `docs/branching.md`: 접두사 표 `문서:`/`정리:` 분리(F9). `docs/dependabot.md`: #56 후속 상태(F10).

### 검증 결과
- **V1**: 배지 3종 shields.io 200(`image/svg+xml`). 해석 버전 Boot 4.0.8·spring-security-core 7.0.7·hibernate-core 7.2.24(정찰 시 `./gradlew dependencies`).
- **V2**: (a) `application.yml`·`application-dev.yml`의 기본값 없는 키 4종(`SPRING_PROFILES_ACTIVE`·`DB_PASS`·`MAIL_USER`·`MAIL_PASS`) ⊆ README 목록, (b) DB 컨테이너 키 4종(`MYSQL_ROOT_PASSWORD`·`MYSQL_DATABASE`·`MYSQL_USER`·`MYSQL_PASSWORD`) README 수록, (c) 계정·DB 이름 일치 문장 존재(README 125·126행). 로컬 `.env.dev`가 README 키 9종을 모두 가짐(키 이름만 비교).
- **V3 실기(폐기용 환경)**: README 형식 임시 env(제한 문자 집합, `MYSQL_DATABASE=cms`)로 `docker run --env-file` MariaDB(`cms-l02-verify`, 3317, compose와 같은 digest) → 같은 env를 셸 환경변수로 주입(`DB_URL=…:3317/cms`·임시 `APP_FILE_STORAGE_ROOT`·`SERVER_PORT=8091`)하고 `./gradlew bootRun` → 빈 스키마에 **22개 마이그레이션 적용(v22)**, `TestMemberLoader`의 `admin` 로그인 성공(302 → `/admin`). `/admin/login` 200, 비인증 `/swagger-ui.html`·`/v3/api-docs` 302 → `/admin/login`, ADMIN `/swagger-ui.html` 302 → `/swagger-ui/index.html`, UI 200 `text/html`, `/v3/api-docs` 200 `application/json`, 공개 `/notices` 200. 정리: 앱 종료·컨테이너 `rm -f -v`·임시 디렉터리 삭제, 8091·3317 해제와 `l02` 컨테이너 0 확인. 기존 dev DB·볼륨·실행 중 dev 스택은 사용하지 않음.
- **V4**: migration-guide 표 16행 모두 열 구분자 4개, V1~V3·V13~V22 각 1회, 마이그레이션 디렉터리 최대 버전 22와 일치.
- **V5**: 수정 문서 6개의 파일 경로·클래스 식별자 재검사 — 새 깨진 참조 0(`spring.mail.properties`는 설정 키라 기존부터 있던 오탐).
- **V6**: `src/test`는 문서를 주석으로만 언급(읽지 않음). `deployment.md` 런북 블록을 읽는 `scripts/tests/quiesced-runbook-test.sh` 전 시나리오 PASS(블록 미수정). 전체 `./gradlew cleanTest test` **1360건, 실패·오류 0**(스킵 3 — Windows 심볼릭 링크).
- UI 변경이 없어 playwright 화면 검증은 대상 아님(V3 HTTP 실기로 대신).

### 코드 리뷰 (`/code-review-loop`, codex `gpt-6.1-sol`, 2라운드)
- 1라운드 지적 2건 수용: (1) 문자 제한을 "모든 값"에 걸어 `MAIL_USER`(이메일)·`DB_URL`을 쓸 수 없게 됨 — v9 축소 때 적용 대상을 너무 넓게 잡은 실수 → 비밀번호·계정명·DB 이름(`DB_USER`·`DB_PASS`·`MAIL_PASS`·`MYSQL_*`)에만 제한, 이메일·URL은 원래 형식. (2) `${DB_USER:admin}`은 변수가 **없을 때만** 기본값 — `DB_USER=` 빈 값은 빈 사용자명으로 접속 → "비우면 admin" 안내 삭제, 반드시 `MYSQL_USER`와 같은 값을 채우도록. **D3의 "`DB_USER`는 생략 시 `admin`" 서술은 이로써 폐기**.
- 2라운드: 지적 0건 통과.

### 이슈
- 실기 첫 로그인 시도가 `/admin/login-error`로 갔다 — 검증 스크립트가 폼 파라미터를 `userId`/`pwd`로 잘못 보낸 것(실제 `username`/`password`, `login.html`). 앱 문제 아님, 수정 후 성공.
- 정리 직후 `[::1]:3317` LISTENING이 남아 보였으나 `wslrelay.exe`(WSL 포트 중계)였고 수 초 뒤 해제.

### 후속
- Compose가 `.env.dev` 값을 셸과 같게 읽는지는 제한 문자 집합으로 문제 자체를 없앴으므로 별도 검증 없음. dev compose healthcheck의 따옴표 없는 `-p$MYSQL_ROOT_PASSWORD`는 코드 개선 여지(범위 밖, 필요 시 별도 작업).
- 로드맵 L-02 완료 반영은 머지 후 `/updateRoadmap`.
- 출처: 로드맵 "우선순위에서 밀린 감사 항목" 표 L-02 — "README Boot 배지(3.4) vs 실제 3.5.16, `deployment.md`의 prod Swagger 404 설명 등 문서-코드 불일치". `/suggestRoadmap`에서 사용자 선택.

> **개정 이력**
> - v9 변경(사용자 결정 U3 — `.env.dev` 안내 축소, 이후 바로 구현): 3~7라운드 지적 7건이 전부 Compose·Bash·healthcheck가 같은 값을 다르게 읽는 형식 조건에서 나와 문장 규칙이 계속 늘어남 → 형식 규칙(작은따옴표·역슬래시·LF·셸 `source` 예시)을 **"모든 값은 영문·숫자·`_`·`-`만"** 한 줄로 대체. 제한된 문자 집합에서는 R3-2·R5-1·R6-1·R6-2·R7-1의 실패 조건이 생기지 않는다. baseline 안내는 "명령을 실행하는 셸의 환경변수로 주입"만 적고 명령 예시는 제거. V3도 `docker run --env-file`과 셸 주입으로 단순화(특수문자 전달 검증 제거). 리뷰 루프는 7라운드에서 종료하고 승인
> - v8 변경(codex `gpt-6.1-sol` 7라운드 — needs-attention, 지적 1건 수용, R6-2 해소 확인):
>   - R7-1 Compose dotenv 파서(compose-go)는 작은따옴표 안에서도 `\'`를 이스케이프로 처리해, 역슬래시로 끝나는 값(`'demo\'`)은 Compose에선 따옴표가 닫히지 않고 Bash에선 `demo\` → 규칙에 "비밀번호 값 끝에 역슬래시를 두지 않는다" 추가(두 번 쓰기는 Bash 값이 달라져 안내하지 않음)
> - v7 변경(codex `gpt-6.1-sol` 6라운드 — needs-attention, 지적 2건 수용):
>   - R6-1 "공백·`$`·`#` 포함 값만 작은따옴표" 규칙은 `;`·`&`·`|`·백틱 등 다른 셸 메타문자를 놓친다(`DB_PASS=demo;true` → Bash에선 `demo`로 잘리고 `true` 실행, Compose는 전체 값) → 열거를 버리고 **비밀번호 값(`DB_PASS`·`MAIL_PASS`·`MYSQL_PASSWORD`)은 항상 작은따옴표, 값 안에 작은따옴표 금지**로 단순화
>   - R6-2 R5-1 사유 "`$`가 있으면 healthy가 되지 못한다"는 틀림 — `mariadb-admin ping`은 인증 실패에도 서버가 살아 있으면 0 반환 → 문자 제한은 유지, 사유를 "셸이 값을 재해석해 healthcheck가 실패하거나 잘못 성공할 수 있다"로 수정
> - v6 변경(codex `gpt-6.1-sol` 5라운드 — needs-attention, 지적 1건 수용):
>   - R5-1 `docker-compose.dev.yml` healthcheck(`CMD-SHELL … -p$MYSQL_ROOT_PASSWORD`)가 값을 따옴표 없이 셸에 넣어, 루트 비밀번호의 공백·`$`가 인자 분리·재해석됨 → DB가 healthy가 되지 못해 `make dev-up`이 막힘(R3-2 파일 파싱과 다른 실행 단계). 코드 변경은 범위 밖이므로 D3에 "`MYSQL_ROOT_PASSWORD`는 영문·숫자·`_`·`-`만" 제약을 명시. 앱 계정 비밀번호의 공백·`$` 규칙과 V3 검증은 유지
> - v5 변경(codex `gpt-6.1-sol` 4라운드 — needs-attention, 지적 1건 수용):
>   - R4-1 v2에서 추가한 "전환 전 백업 → V16/V22 백업 절 링크"가 `make prod-backup`(대상 `cms-db-prod` 고정)으로 이어져, dev·별도 DB를 전환하는 독자가 **다른 DB를 백업하고 성공으로 오인**할 수 있음 → D6 F11에 "사전 점검·백업·baseline은 같은 대상 DB, `make prod-backup`은 prod 스택 전용 — 그 외 DB는 해당 DB의 `mariadb-dump`를 먼저 확보" 명시. 스크립트·실기 변경 없음
> - v4 변경(codex `gpt-6.1-sol` 3라운드 — needs-attention, 지적 2건: 1건 부분 수용·1건 수용):
>   - R3-1(부분 수용) 호스트 셸 변수가 Compose 치환(`${MYSQL_DATABASE}`)에서 `--env-file`보다 우선 → D3에 "dev 스택 기동 전 셸에 같은 이름(`MYSQL_*` 등)의 낡은 변수가 없는지 확인" 한 줄 추가(`prod-up.sh`가 같은 이유로 unset하는 것과 같은 함정). **기각 부분**: V2에 "충돌 조건에서 Compose 해석 결과 검증" 추가는 하지 않는다 — 셸에 다른 값이 남은 드문 사용자 환경 대비를 위해 검증 절차를 늘리는 것은 문서 정합 범위에 과하고, 이번에 안내하는 `set -a; . ./.env.dev` 주입은 같은 파일 값이라 충돌을 만들지 않는다
>   - R3-2(수용) Compose env 파일 문법(등호 주변 공백·따옴표 없는 공백 값 허용)은 Bash `source`와 다르고, Windows CRLF 파일은 `source` 시 값 끝에 `\r`이 붙는다 → D3에 공통 형식 규칙(등호 앞뒤 공백 없음, 공백·`$` 포함 값은 작은따옴표 — Compose·Bash 모두 리터럴, LF 줄바꿈) 명시, F11 baseline 안내에서도 같은 규칙 참조. V3 DB 비밀번호를 공백·`$` 포함 가짜 값으로 바꿔 Bash 경유 전달을 끝까지 검증(Compose 쪽 작은따옴표 해석은 Docker 문서 계약으로 두고 실기 범위 밖 — 한계로 기록)
> - v3 변경(codex `gpt-6.1-sol` 2라운드 — needs-attention, 지적 2건 전부 수용):
>   - R2-1 `migration-guide.md`의 baseline 명령(`SPRING_FLYWAY_BASELINE_ON_MIGRATE=true ./gradlew bootRun`)은 프로파일·DB·메일 환경변수를 같은 프로세스에 주지 않으면 기동 실패(IntelliJ 설정·`.env.dev` 파일은 터미널 `bootRun`에 전달되지 않음) → D6 F11에 "같은 셸에 env 주입(`set -a; . ./.env.dev; set +a` 등) + `DB_URL`이 사전 점검한 DB를 가리키는지 확인" 전제 추가. §5 V3에도 env 전달 방법 명시
>   - R2-2 MariaDB 공식 이미지의 `MYSQL_*`는 빈 데이터 디렉터리 최초 초기화에만 적용 — 기존 `cms_db_data_dev` 볼륨을 재사용하며 `.env.dev`의 DB 이름·계정을 바꾸면 값끼리 일치해도 접속 실패 → D3에 "기존 볼륨이면 처음 만든 DB 이름·계정을 유지, 실패 시 볼륨 삭제가 아니라 기존 값 확인" 문장 추가
>   - 참고: 리뷰어가 네트워크 제한으로 미확인이라 한 PR #56 종료·브랜치 보호는 정찰에서 `gh pr view 56`(CLOSED, 2026-09-29)·`gh api …/protection`(`test`·`prod-smoke` 필수, 관리자 포함)으로 확인 완료
> - v2 변경(codex `gpt-6.1-sol` 1라운드 — needs-attention, 지적 5건 전부 수용):
>   - R1-1(높음) V3 실기가 dev DB를 로그인 1행 이상으로 바꾼다(기동 시 Flyway 미적용분·프로필 이미지 이관 러너, 로그인 시 알림 생성·정리) → **V3를 폐기용 MariaDB 컨테이너(별도 이름·포트·볼륨 없음) + 임시 스토리지 경로**로 수행, 기존 dev DB·볼륨은 건드리지 않음. §5 V3·§6 리스크 행 교체
>   - R1-2 D3에 DB 이름 일치 조건 누락 → `DB_URL` 생략 시 `MYSQL_DATABASE=cms`여야 함, 다른 이름이면 `DB_URL`을 같은 이름으로 지정해 IntelliJ에도 주입. D3 갱신
>   - R1-3 V2 "기본값 없는 키 = README 목록" 동등 검사는 `DB_USER`(기본값 `admin`)·`env_file` 경유 `MYSQL_*` 때문에 성립하지 않음 → 검사를 세 계약으로 분리(필수 placeholder ⊆ README, DB 컨테이너 키는 compose·MariaDB 이미지 계약으로 확인, 계정·DB 이름 일치는 문장으로 명시). §5 V2 갱신
>   - R1-4 `/swagger-ui.html`은 springdoc 3.0.3에서 302로 UI 경로에 리다이렉트 — "ADMIN 로그인 후 200"은 틀린 기대 → 비인증 302→`/admin/login`, ADMIN 302→`/swagger-ui/index.html`, 그 UI 200을 각각 검사. README의 prod 설명도 "ADMIN 로그인 후에도 404"로 조건 명시. D4·§5 V3 갱신
>   - R1-5 `migration-guide.md` "기존 DB 전환 절차"가 baseline 후 V2·V3만 적용된다고 설명 — 날짜 기록이 아니라 현재 절차이므로 D1 예외에 해당 → "V2부터 최신 버전까지 적용(V16·V22 파괴적 변경 포함, 백업 선행)", 확인 기준을 "모든 행 success=1·최신 버전"으로. F11·D6 추가
> - v1: 최초 작성(정찰 + 사용자 결정 U1·U2 반영)

## 1. 범위

**문서만 수정한다.** 코드·설정·스키마·인가 정책·의존성 변경 없음.

대상: `README.md`, `docs/deployment.md`, `docs/verification/deployment-edge.md`, `docs/migration-guide.md`, `docs/branching.md`, `docs/dependabot.md`.

## 2. 정찰 결과 — 코드 대조로 확인한 불일치

검증 수단: `./gradlew dependencies --configuration runtimeClasspath`(실제 해석 버전), `src/main/resources/db/migration/` 목록, `ClientIpResolver` 소스, `build.gradle` `ext[...]`, `git log` 커밋 접두사 분포, `gh pr view 56`, `docker-compose.dev.yml`·`application-dev.yml` 변수 참조.

| # | 위치 | 문서 | 실제(근거) |
|---|---|---|---|
| F1 | README 배지 4~6행 | Spring Boot 3.5 · Spring Security 6 · Hibernate 6 | Boot 4.0.8(`build.gradle` 플러그인) · spring-security-core 7.0.7 · hibernate-core 7.2.24(의존성 해석) |
| F2 | README 주요 기능 "파일 스토리지" | 공지 첨부파일과 프로필 이미지를 DB Base64에서 디스크로 **이관** | 공지 첨부는 도입(#18)부터 `FileStorage` 저장. Base64→FileStorage 이관은 프로필 이미지만(#28, `ProfileImageMigrationRunner`) |
| F3 | README Quick Start | 단계 번호 1·2·3·4·**6** | 5 누락(번호 오류) |
| F4 | `deployment.md` "배포 대상(ingress)" 294행 | `AdminActionLogAspect`가 `X-FORWARDED-FOR`/`X-Real-IP`를 검증 없이 우선 사용 → 레이트리밋과 IP 소스 불일치, 범위 밖 | #44(`d8952ef`, H-03)로 해소 — `ClientIpResolver.resolve()`가 `getRemoteAddr()`만 사용하고 감사 로그·방문 로그·비밀번호 재설정이 공유 |
| F5 | `verification/deployment-edge.md` 체크리스트 7번째 항목 | 같은 불일치를 "(전달 헤더 우선 사용)"으로 기술 | F4와 같음 |
| F6 | `deployment.md` "알려진 제약" 377행 | 오프사이트 백업은 "후속 과제로 로드맵에 기록 예정" | 로드맵 "후속 과제 — ①"에 이미 기록됨(2026-08-12) |
| F7 | `deployment.md` "스캔 실패 시 절차" 315행 | BOM 오버라이드 `tomcat.version`·`jackson-bom.version` | `jackson-2-bom.version`까지 3개(`build.gradle` 15·16·20행) |
| F8 | `migration-guide.md` "현재 마이그레이션 구성" 표 | V4~V12 행 뒤에 V13·V14·V15·V17·V18·V19·V16 행이 `||`로 한 줄에 붙어 렌더링이 깨짐, V16이 V19 뒤, V20·V21 행 없음 | 파일은 V1~V22 연속(`V20__create_notification.sql`·`V21__create_admin_message.sql`) |
| F9 | `branching.md` 브랜치 네이밍 표 | 문서/잡일(`docs/`, `chore/`) → 커밋 접두사 `정리:` | 문서 변경은 `문서:` 사용(이력 11회, #98·#100·#102). `정리:`는 잡일·제거(21회) |
| F10 | `dependabot.md` 첫 실행 표 | #56 gradle-wrapper "열어 둠" | 2026-09-29 Dependabot이 직접 닫음("no longer being updated" — 같은 날 추가한 메이저 무시 설정 반영). 현재 wrapper 8.14.5 |
| F11 | `migration-guide.md` "기존 DB 전환 절차" 2·3단계 | baseline 1회차 기동은 "V2·V3 적용(둘 다 no-op)", 확인 기대값 "Baseline(1), V2, V3 모두 success=1" | 지금 baseline하면 V2~V22 전부 적용(V16 `DROP COLUMN access_role`·V22 `DROP TABLE` 포함) — R1-5 |
| G1 | README Quick Start·개발환경 | `make dev-db`/`dev-up`/IntelliJ 실행 안내에 `.env.dev` 준비 단계 없음 | `scripts/dev-db.sh`·`docker-compose.dev.yml`이 `--env-file .env.dev`·`env_file: .env.dev` 필수. 앱은 `SPRING_PROFILES_ACTIVE`(기본값 없음)·`DB_PASS`·`MAIL_USER`·`MAIL_PASS` 필요, DB 컨테이너는 `MYSQL_ROOT_PASSWORD`·`MYSQL_DATABASE`·`MYSQL_USER`·`MYSQL_PASSWORD` 필요. `.env.example`은 prod용 |
| G2 | README 개발환경·API Documentation | Swagger URL만 안내 | dev 프로파일 전용 + `ROLE_ADMIN` 로그인 필요, prod는 핸들러 미등록(404) |
| G3 | README 주요 기능 | 공지사항·공개 공지 페이지·권한관리(회원별 위임)·상단바 알림·쪽지 없음 | 모두 구현·머지됨(#16·#21·#25·#81~#86·#92·#94·#101) |

정합 확인(수정 불필요): 문서가 참조하는 파일 경로·클래스명 전수 실존(스크립트 검사), make 타깃 목록, `prod-up.sh` health 폴링 60초, prod 볼륨명, digest 고정 위치 수(Dockerfile 2·compose 2), `TestMemberLoader` `admin`/`1234`, `deployment.md` prod Swagger 404 설명(L-02 원문 지적은 #26 이후 이미 정합), 브랜치 보호(`test`·`prod-smoke` 필수, 관리자 포함 — `gh api`로 확인).

## 3. 설계 결정

### D1. 이력 문서는 다시 쓰지 않는다
- 선택지: (a) 모든 문서의 낡은 서술을 현재 기준으로 갱신 (b) **현재 상태를 설명하는 서술만 고치고, 날짜가 박힌 기록·실증 로그는 그대로 둔다**
- 결정: (b). `troubleshooting.md`·`docs/plans/`·`docs/verification/recovery-drill.md`·`admin-detail-rendering.md`, `dependabot.md` "소음 관리 결정 (2026-09-29)", `deployment.md` "권한관리 롤백 주의"(V22 이전 DB 전용임을 이미 인용 블록으로 한정)는 당시 사실의 기록이다.
- 왜: 이력을 현재 기준으로 고치면 "그때 무엇을 알았나"가 사라진다. 예외는 **현재 시제로 지금도 참인 것처럼 읽히는 문장**(F4·F5·F6), **독자가 지금 따라 실행하는 절차**(F11 — 작성일이 붙은 문서라도 절차 본문은 현재 기준이어야 한다)와 **나중에 바뀐 결과를 표가 단정하는 경우**(F10)다. F10은 행을 고치지 않고 표 아래에 후속 상태를 덧붙인다(스냅샷 보존).

### D2. 배지 버전 정밀도 — major.minor
- 선택지: (a) 패치까지(`4.0.8`) (b) **major.minor(`4.0`)** (c) major만(`4`)
- 결정: Spring Boot `4.0`, Spring Security `7`, Hibernate `7`. Java `17` 유지.
- 왜: 기존 배지가 Boot는 minor(`3.5`), Security·Hibernate는 major로 표기하던 관례를 유지한다. 패치까지 적으면 Dependabot 패치 PR마다 README가 낡는다(이번 L-02가 생긴 원인과 같은 구조). Security·Hibernate는 Boot BOM이 정하므로 major만으로 충분하다.

### D3. `.env.dev` 준비 단계 (사용자 결정 U1: 포함)
- 선택지: (a) `.env.dev.example` 파일 신설 (b) **README에 필수 키 목록만(값 없이) 추가**
- 결정: (b). Quick Start에 "5️⃣ `.env.dev` 작성" 단계를 넣어 F3(번호 누락)도 함께 해소. 키는 `SPRING_PROFILES_ACTIVE=dev`(값 고정 안내), `DB_USER`·`DB_PASS`, `MAIL_USER`·`MAIL_PASS`, `MYSQL_ROOT_PASSWORD`·`MYSQL_DATABASE`·`MYSQL_USER`·`MYSQL_PASSWORD`. `DB_URL`은 선택 — 생략하면 `application-dev.yml` 기본값 `localhost:3307`(IntelliJ 실행용), `make dev-up`의 app 컨테이너는 compose가 `db:3306`으로 덮어쓴다는 점을 한 줄로 적는다.
- `DB_USER`·`DB_PASS`는 DB 컨테이너의 `MYSQL_USER`·`MYSQL_PASSWORD`와 **같은 값**이어야 한다는 점을 명시(dev compose는 prod처럼 직접 참조하지 않고 두 키를 따로 읽는다). `DB_USER`는 생략 시 `admin`(기본값)이므로 그때는 `MYSQL_USER=admin`이어야 한다.
- **DB 이름 일치(R1-2)**: `DB_URL`을 생략하면 앱은 `localhost:3307/cms`에 접속하므로 `MYSQL_DATABASE=cms`여야 한다. 다른 이름을 쓰면 `DB_URL=jdbc:mariadb://localhost:3307/<그 이름>`을 넣는다(`make dev-up`의 app 컨테이너는 compose가 `db:3306/${MYSQL_DATABASE}`로 덮어쓰므로 영향 없음).
- **기존 볼륨 주의(R2-2)**: `MYSQL_*`는 DB 볼륨이 비어 있을 때 최초 1회만 적용된다(MariaDB 공식 이미지 동작). `make dev-down`은 볼륨을 보존하므로, 이미 만든 `cms_db_data_dev`를 쓰는 동안 `.env.dev`의 DB 이름·계정·비밀번호를 바꾸면 값끼리 맞아도 접속이 실패한다 — 처음 만든 값을 유지하고, 실패하면 볼륨을 지우기 전에 기존 값을 확인하라고 적는다.
- **값 형식(v9 사용자 결정 U3 — 안내 축소)**: `KEY=value`(등호 앞뒤 공백 없음, 따옴표 없이), **모든 값은 영문·숫자·`_`·`-`만** 쓴다고 한 줄로 안내한다. 근거 한 구절: dev compose healthcheck가 `MYSQL_ROOT_PASSWORD`를 따옴표 없이 셸 명령에 넣어 공백·특수문자가 재해석되고(실패하거나 잘못 성공 — R5-1·R6-2), Compose와 셸이 따옴표·역슬래시를 다르게 읽는다(R3-2·R6-1·R7-1). dev용 값이라 문자 제한이 실사용에 문제되지 않고, 파서 차이 규칙을 문장으로 나열하지 않아도 된다. (이전 v4~v8의 작은따옴표·역슬래시·LF 규칙은 이 한 줄로 대체 — 그 지적들은 제한된 문자 집합에서 발생하지 않는다.)
- **셸 변수 우선(R3-1)**: Compose는 셸에 이미 있는 같은 이름의 변수를 `.env.dev`보다 우선해 `${MYSQL_DATABASE}`를 치환한다 — dev 스택 기동 전에 셸에 낡은 `MYSQL_*` 등이 남아 있지 않은지 확인하라고 한 줄 적는다.
- IntelliJ 실행 절에는 "Active Profile 지정 + `.env.dev` 값을 환경변수로 주입"을 적는다(`DB_URL`은 위 규칙대로 — 넣었다면 `localhost` 기준이어야 한다).
- 왜 (a)가 아닌가: 새 파일은 `.gitignore` 예외 규칙(`.env*`, `!.env.example`)까지 바꿔야 하고 "요청받지 않은 설정 추가"에 해당한다. 키 목록은 README만으로 충분히 전달된다.

### D4. Swagger 접근 조건 (U1에 포함)
- README "개발환경" 접속 목록과 "API Documentation" 절에 "dev 프로파일 전용, ADMIN 로그인 필요(비로그인은 로그인 화면으로 이동). prod는 springdoc 비활성이라 ADMIN 로그인 후에도 404"를 한 줄 추가(R1-4 — `SecurityConfig`의 `hasRole("ADMIN")`은 prod에도 적용되므로 prod 비인증은 404가 아니라 로그인 리다이렉트). 상세는 `docs/deployment.md` "prod에서 잠기는 항목" 링크.

### D5. 주요 기능 보강 (사용자 결정 U2: 추가)
- 기존 항목 문체(굵은 이름 + 콜론 + 한 줄)로 추가:
  - **콘텐츠(공지사항)**: 관리자 CRUD·검색·첨부파일(공지당 5개·파일당 10MB) + 비로그인 공개 공지 목록·상세·첨부 다운로드(공개 조건 재검증, 스트리밍 전송)
  - **위임 권한 관리**: ADMIN이 MANAGER 회원별로 공지 조회·생성·수정·삭제 권한을 부여·회수(코드 카탈로그 ∩ 회원별 허용 행, ADMIN은 항상 허용)
  - **상단바 알림·쪽지**: 관리자 대상 알림(벨)과 관리자 간 1:1 쪽지·쪽지함
- "인증/인가" 항목: "Role(ADMIN/MANAGER) 기반 URL·메서드 이중 접근 제어"에 "+ MANAGER 회원별 위임 권한"을 덧붙인다. README "Security" 절도 같은 한 줄을 추가.
- F2 수정: "공지 첨부파일을 디스크 기반 파일 스토리지(`FileStorage` 추상화)에 저장하고, 회원 프로필 이미지는 기존 DB Base64 저장에서 이관"
- 수치(5개·10MB)는 코드 상수로 재확인한 뒤 적는다(구현 단계 첫 작업). 확인되지 않으면 수치를 빼고 적는다.
- 왜: 기능 목록은 README 독자가 프로젝트 범위를 판단하는 유일한 요약이다. 과장 없이 구현된 것만, 기존 항목과 같은 밀도로 적는다. 알림·쪽지는 한 항목으로 묶어 목록이 길어지지 않게 한다.

### D6. migration-guide 표 재구성
- V1~V3 상세 행 유지, V4~V12 묶음 행 유지, 그 뒤 V13~V22를 **버전 순으로 한 행씩**. 기존 행의 설명 문구는 그대로 옮기고 V20(`notification`)·V21(`admin_message`·`admin_message_sender_state`·`admin_message_send_log`, DDL 3문)만 새로 쓴다(내용은 같은 문서의 "V20/V21 실패 복구" 항목과 SQL 파일로 확인).
- 왜: 지금 표는 마크다운에서 한 행으로 붙어 렌더링돼 읽을 수 없다(행 구분 개행 누락). 내용 변경 없이 형식·순서·누락만 고친다.
- **F11(R1-5) 전환 절차 갱신**: "환경별 동작"의 "baseline 후 V1은 건너뛰고 V2부터 적용"은 정확하므로 유지. "2. 일회성 baseline 기동"의 1회차 설명을 "baseline(version 1) 기록 + V2부터 최신 버전까지 적용 — V16(`menu.access_role` 제거)·V22(역할 단위 권한 테이블 제거)는 되돌릴 수 없으므로 전환 전에 DB 백업"으로, "3. 전환 확인"의 기대값을 "Baseline(1)과 V2~최신 버전 모든 행 `success=1`, 마지막 버전 = `src/main/resources/db/migration/`의 최대 버전"으로 바꾼다. 백업·복구는 같은 문서의 "V16/V22 배포 전 백업과 복구" 절로 링크하되, **대상 일치(R4-1)**를 명시한다 — 사전 점검·백업·baseline은 모두 같은 대상 DB여야 하고, `make prod-backup`은 prod 스택(`cms-db-prod`) 전용이므로 dev·별도 DB를 전환할 때는 그 DB의 `mariadb-dump`를 먼저 확보한다. **실행 전제(R2-1)**: baseline 명령은 앱 기동이므로 프로파일(`SPRING_PROFILES_ACTIVE`)·DB·메일 환경변수가 **명령을 실행하는 셸의 환경변수로** 주입돼 있어야 한다(IntelliJ 실행 설정이나 `.env.dev` 파일 존재만으로는 터미널 `bootRun`에 전달되지 않는다 — v9: 셸 주입 명령 예시는 적지 않는다). 기동 전에 `DB_URL`(생략 시 기본값)이 1단계에서 사전 점검한 바로 그 DB를 가리키는지 확인한다는 문장을 추가. 최신 버전 숫자(22)를 본문에 박지 않는다 — 다음 마이그레이션 때 또 낡지 않도록 "디렉터리의 최대 버전"으로 표현.

### D7. branching.md 접두사 표
- "문서/잡일" 한 행을 "문서 | `docs/`, `chore/` | `문서:`"와 "잡일·정리 | `chore/` | `정리:`" 두 행으로 나눈다. 루트 `CLAUDE.md`의 브랜치 접두사 목록(`docs/` 없음)과의 차이는 이번 범위에서 고치지 않는다 — 실제 이력에 `docs/` 브랜치 3건·`chore/` 문서 브랜치 다수가 공존해 어느 쪽도 "사실 오류"가 아니다. 이번 브랜치는 루트 `CLAUDE.md`를 따라 `chore/`.

## 4. 작업 단계

1. 브랜치 `chore/doc-consistency-l02` 생성.
2. D5 수치 확인(공지 첨부 상한 상수) → README 수정(F1·F2·F3·G1·G2·G3).
3. `deployment.md`(F4·F6·F7), `verification/deployment-edge.md`(F5) 수정.
4. `migration-guide.md` 표 재구성(F8)·전환 절차 갱신(F11).
5. `branching.md`(F9), `dependabot.md`(F10) 수정.
6. 검증(§5).
7. 기록: 이 문서에 구현·검증 결과, `plan/README.md` 31행, 로드맵 L-02는 머지 후 `/updateRoadmap`.

## 5. 검증

문서만 바뀌므로 동작 테스트가 대상이 아니다. 대신 **바꾼 문장 하나하나가 코드와 맞는지**를 기계적으로 다시 확인한다.

- **V1 버전**: `./gradlew dependencies`로 Boot·Security·Hibernate major 재확인, 배지 URL이 shields.io에서 200(이미지 응답)인지 `curl -sI`.
- **V2 env 키(R1-3 — 세 계약으로 분리)**: (a) 앱 필수 키 — `application.yml`·`application-dev.yml`의 `${...}` 중 기본값 없는 키(`SPRING_PROFILES_ACTIVE`·`DB_PASS`·`MAIL_USER`·`MAIL_PASS`)가 README 목록에 **포함**되는지(⊆ 검사). (b) DB 컨테이너 키 — `docker-compose.dev.yml`의 `env_file: .env.dev`와 MariaDB 이미지가 요구하는 `MYSQL_ROOT_PASSWORD`(healthcheck도 참조)·`MYSQL_DATABASE`·`MYSQL_USER`·`MYSQL_PASSWORD`가 README에 있는지 — 단순 placeholder 검색으로는 추출되지 않으므로 compose 파일과 이미지 계약을 근거로 목록을 고정해 대조. (c) 일치 계약 — 계정(`DB_USER`/`DB_PASS` ↔ `MYSQL_USER`/`MYSQL_PASSWORD`)·DB 이름(`DB_URL` 생략 시 `MYSQL_DATABASE=cms`)이 README 문장에 있는지 확인. 선택 키(`DB_USER`·`DB_URL`)는 선택으로 표기됐는지. 값은 출력하지 않는다.
- **V3 실행(R1-1 — 폐기용 환경)**: 기존 dev DB·볼륨(`cms-db-dev`·`cms_db_data_dev`)은 쓰지 않는다. README가 안내하는 키 구성·값 형식(영문·숫자·`_`·`-`만, `MYSQL_DATABASE=cms`, `DB_USER`/`DB_PASS` = `MYSQL_USER`/`MYSQL_PASSWORD`)으로 만든 임시 env 파일(스크래치 디렉터리)을 `docker run --env-file <임시 env>`로 넘겨 **별도 이름·포트(예: `cms-l02-verify`, 3317)·익명 볼륨**의 MariaDB 컨테이너를 같은 digest 이미지로 띄우고, 같은 파일의 앱 키를 셸 환경변수로 주입(R2-1)한 뒤 `DB_URL=jdbc:mariadb://localhost:3317/cms`·`APP_FILE_STORAGE_ROOT=<스크래치 임시 디렉터리>`·`SPRING_PROFILES_ACTIVE=dev`로 `./gradlew bootRun` 기동. 제한된 문자 집합이라 Compose·`docker run`·셸이 같은 값을 읽는다. 빈 DB라 Flyway V1~최신 전체 적용과 `TestMemberLoader`의 `admin` 생성이 함께 검증된다. 확인: `/admin/login` 200, `/swagger-ui.html` 비인증 302 → `/admin/login`, ADMIN 로그인 세션으로 `/swagger-ui.html` 302 → `/swagger-ui/index.html`, 그 경로 200, `/v3/api-docs` 200. 포트 3307을 기본값으로 쓰는 "`DB_URL` 생략" 경로는 기존 dev DB와 겹치므로 실기하지 않고 `application-dev.yml` 기본값 문자열로 확인한다. 종료 시 앱 정지 → 컨테이너 `docker rm -f`(익명 볼륨 `-v`로 함께 삭제) → 임시 디렉터리 삭제, 남은 컨테이너·볼륨이 없는지 `docker ps -a`·`docker volume ls`로 확인.
- **V4 표 렌더링**: migration-guide 표의 각 행 열 수가 머리 행과 같은지 awk로 검사, V1~V22 각 버전이 표에 정확히 한 번(V4~V12는 묶음 행) 나오는지 확인.
- **V5 경로·식별자**: 정찰에 쓴 실존 검사 스크립트를 수정 후 문서에 다시 돌려 새로 생긴 깨진 참조가 0인지.
- **V6 회귀**: `./gradlew test`는 문서 변경과 무관하지만 프로젝트 규칙에 따라 전체 실행(문서 리소스를 읽는 테스트가 없는지도 함께 확인 — `src/test`에서 `README`·`docs/` 문자열 grep).
- 브라우저 화면 변경이 없으므로 playwright UI 검증은 대상 아님. V3의 실기로 대신한다.

## 6. 리스크

| 리스크 | 대응 |
|---|---|
| 새로 쓴 문장이 또 다른 부정확을 만든다(특히 D5 기능 요약·D3 env 설명) | 문장마다 근거 코드/설정을 §5로 재대조. 수치는 상수 확인 전엔 넣지 않는다 |
| README `.env.dev` 키 목록이 이후 설정 변경으로 다시 낡는다 | 근거 파일(`application-dev.yml`·`docker-compose.dev.yml`)을 README에 명시해 갱신 지점을 드러낸다. 자동 검사는 추가하지 않는다(과설계) |
| 이력 문서를 고쳐 기록 가치를 훼손 | D1 — 현재 시제 오류만, 스냅샷은 덧붙이기 |
| V3 실기가 로컬 dev DB·파일을 바꾼다(기동 시 Flyway·이관 러너, 로그인 시 방문·알림 쓰기 — R1-1) | 기존 dev DB·볼륨을 쓰지 않고 폐기용 컨테이너·임시 스토리지로만 수행, 종료 후 잔여 자원 0 확인 |
| V3에서 포트 8080·3317이 이미 사용 중 | 기동 전 확인 후 사용 중이면 다른 포트로 바꾸고 그 사실을 결과에 기록(dev 스택이 8080을 쓰면 정지 여부를 사용자에게 묻는다) |
