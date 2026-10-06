# CMS (Content Management System)

![Java](https://img.shields.io/badge/Java-17-blue)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0-brightgreen)
![Spring Security](https://img.shields.io/badge/Spring%20Security-7-green)
![Spring Data JPA](https://img.shields.io/badge/Spring%20Data%20JPA-Hibernate%207-yellowgreen)
![Thymeleaf](https://img.shields.io/badge/Thymeleaf-Template-darkgreen)
![MariaDB](https://img.shields.io/badge/MariaDB-Database-orange)
![Gradle](https://img.shields.io/badge/Gradle-Build-blueviolet)
![Docker](https://img.shields.io/badge/Docker-Container-blue)
![Docker Compose](https://img.shields.io/badge/Docker%20Compose-Orchestration-2496ED)
![Swagger](https://img.shields.io/badge/Swagger-OpenAPI-lightgreen)

Spring Boot 기반 관리자 CMS 프로젝트입니다.  
Docker를 활용하여 개발 환경과 통합 실행 환경을 분리하였으며,  
Spring Security 및 Swagger 문서화를 적용했습니다.

소스 코드와 문서는 UTF-8 인코딩을 기준으로 작성합니다. 에디터 설정은 루트의 `.editorconfig`를 따릅니다.

---

## ✨ 주요 기능

단순 CRUD를 넘어 운영 환경에서 요구되는 인증/인가·동시성·감사·복구 요소까지 구현했습니다.

- **콘텐츠(공지사항)**: 관리자 공지 CRUD·제목 검색·첨부파일(공지당 5개, 파일당 10MB) + 비로그인 공개 공지 목록·상세·첨부 다운로드(요청마다 공개 조건 재검증, 스트리밍 전송)
- **인증/인가**: 세션 기반 로그인, Role(ADMIN/MANAGER) 기반 URL·메서드 이중 접근 제어, 로그인 5회 연속 실패 시 자동 잠금(30분), 비밀번호 90일 만료
- **위임 권한 관리**: ADMIN이 MANAGER 회원별로 공지 조회·생성·수정·삭제 권한을 부여·회수(코드 카탈로그 ∩ 회원별 허용 행으로 판정, ADMIN은 항상 허용)
- **세션 보안**: 타 관리자가 대상 계정의 상태·권한을 실제로 변경하면 대상자 세션을 강제 만료 처리(이벤트 기반 best-effort, 커밋 이후 발동)
- **CSRF·레이트리밋**: 전 경로 CSRF 보호 + 무인증 공개 엔드포인트(공지 조회, 비밀번호 재설정 요청)에 토큰 버킷 기반 레이트리밋 적용
- **감사 로그**: `@AdminActionLogged` AOP로 관리자 행위를 자동 기록하며, 원 트랜잭션이 롤백돼도 별도 트랜잭션(REQUIRES_NEW)으로 로그는 보존
- **동시성 제어**: 회원·메뉴·공지·비밀번호 재설정에 낙관적/비관적 락을 적용해 동시 수정 충돌을 방지 (마지막 활성 ADMIN 계정 보호 가드 포함)
- **파일 스토리지**: 공지 첨부파일을 디스크 기반 파일 스토리지(`FileStorage` 추상화)에 저장하고, 회원 프로필 이미지는 기존 DB Base64 저장에서 이관
- **대시보드**: 통계 카드 4종 + 최근 7일 방문자 추이 차트(KST 단일 시간원 기준 집계)
- **상단바 알림·쪽지**: 계정 상태·권한 변경, 비밀번호 만료 임박, 다른 관리자 계정 잠금 알림(벨) + 관리자 간 1:1 쪽지·쪽지함
- **운영 지원**: prod 프로파일 DB/파일 백업·복구 스크립트(`make prod-backup`), Flyway 기반 스키마 버전 관리

---

## 🛠 문제 해결 기록 (Troubleshooting)

프로젝트 개발 과정에서 발생한 주요 이슈와 해결 방법을 정리했습니다.

자세한 내용은 아래 문서를 참고하세요.

➡ `docs/troubleshooting.md`

---

# 🚀 Quick Start (Windows + WSL2 기준)

> Docker Desktop 설치 필요  
> Windows 사용자는 WSL2 기반 Ubuntu 환경에서 실행 권장

---

## 1️⃣ Ubuntu (WSL2) 설치

PowerShell에서 실행:

```powershell
wsl --install -d Ubuntu
```

설치 확인:

```powershell
wsl -l -v
```

Ubuntu가 VERSION 2로 표시되면 정상.

---

## 2️⃣ Ubuntu 실행

```powershell
wsl -d Ubuntu
```

---

## 3️⃣ make 설치

Ubuntu 터미널에서:

```bash
sudo apt update
sudo apt install -y make
```

---

## 4️⃣ 스크립트 실행 권한 부여 (최초 1회)

```bash
sudo chmod +x scripts/*.sh
```
---

## 5️⃣ `.env.dev` 작성 (최초 1회)

dev 스택(`make dev-db`·`make dev-up`)과 IntelliJ 실행 모두 저장소 루트의 `.env.dev`를 읽는다. 커밋하지 않는다(`.gitignore`의 `.env*` 규칙). `.env.example`은 prod용이라 키 구성이 다르다.

```
SPRING_PROFILES_ACTIVE=dev

# 앱 DB 계정 — 아래 MYSQL_USER·MYSQL_PASSWORD와 같은 값
DB_USER=
DB_PASS=
# DB_URL=   (선택 — 아래 "DB 이름" 참고)

# 비밀번호 재설정 메일(SMTP)
MAIL_USER=
MAIL_PASS=

# DB 컨테이너 초기화
MYSQL_ROOT_PASSWORD=
MYSQL_DATABASE=cms
MYSQL_USER=
MYSQL_PASSWORD=
```

- **값 형식**: `KEY=value`(등호 앞뒤 공백·따옴표 없이). 비밀번호·계정명·DB 이름(`DB_USER`·`DB_PASS`·`MAIL_PASS`·`MYSQL_*`)은 **영문·숫자·`_`·`-`만** 쓴다 — dev DB healthcheck가 `MYSQL_ROOT_PASSWORD`를 따옴표 없이 셸 명령에 넣고, Compose와 셸이 따옴표·특수문자를 다르게 해석하기 때문이다. `MAIL_USER`(이메일 주소)와 `DB_URL`은 원래 형식 그대로 쓴다.
- **계정 일치**: `DB_USER`·`DB_PASS`는 `MYSQL_USER`·`MYSQL_PASSWORD`와 같은 값을 적는다. `DB_USER=`처럼 비워 두면 기본값이 아니라 빈 사용자명으로 접속하므로 반드시 채운다.
- **DB 이름**: `DB_URL`을 생략하면 앱은 `localhost:3307/cms`에 접속하므로 `MYSQL_DATABASE=cms`여야 한다. 다른 이름을 쓰면 `DB_URL=jdbc:mariadb://localhost:3307/<그 이름>`을 넣는다(`make dev-up`의 app 컨테이너는 compose가 `db:3306/${MYSQL_DATABASE}`로 덮어쓴다).
- **기존 DB 볼륨**: `MYSQL_*`는 DB 볼륨(`cms_db_data_dev`)이 비어 있을 때 최초 1회만 적용된다. `make dev-down`은 볼륨을 보존하므로, 이미 만든 볼륨을 쓰는 동안 DB 이름·계정·비밀번호를 바꾸면 접속에 실패한다 — 처음 값을 유지하고, 실패하면 볼륨을 지우기 전에 기존 값을 확인한다.
- **셸 변수 우선**: Compose는 셸에 이미 있는 같은 이름의 변수(`MYSQL_DATABASE` 등)를 `.env.dev`보다 우선한다. dev 스택을 띄우기 전에 셸에 낡은 값이 남아 있지 않은지 확인한다.

근거 설정: `src/main/resources/application-dev.yml`(앱), `docker-compose.dev.yml`(DB 컨테이너).

---

## 6️⃣ 명령어 목록 확인

```bash
make help
```

---

# 🐳 주요 실행 명령어

### DB만 실행 (개발용)

```bash
make dev-db
```

### 통합 실행 (app + db)

```bash
make dev-up
```

### 종료

```bash
make dev-down
```

# 🔎 Utility Commands

```bash
make logs        # 전체 로그
make logs-app    # app 로그
make logs-db     # db 로그
make ps          # 컨테이너 상태 확인
make rebuild     # 캐시 없이 재빌드
make prune       # docker build 캐시 정리
```

---

# 🚢 배포 (prod 프로파일)

`SPRING_PROFILES_ACTIVE=prod`로 기동하면 Swagger 비활성·시크릿 전량 환경변수 주입·actuator 최소 노출이 보장된다. 실제 인터넷 배포(호스트·도메인·TLS)는 별도 사안이며, 아래 명령은 로컬/서버에서 "배포 가능한 상태"를 검증하는 용도다.

```bash
make prod-up     # .env.prod 필요 (.env.example 참고) — 기동 후 /actuator/health 폴링까지 자동 수행
make prod-down   # 데이터 볼륨 보존하며 중지
make logs-prod   # prod 로그
```

자세한 절차·필수 환경변수·초기 관리자 계정 생성 규칙은 [docs/deployment.md](docs/deployment.md) 참고.

---

## 개발환경 (IntelliJ + Docker DB)

1. DB 실행
   ```
   make dev-db
   ```

2. IntelliJ 실행
    - Active Profile: `dev`
    - 실행 설정의 환경변수에 `.env.dev` 값을 주입(위 5️⃣ — `DB_URL`을 넣었다면 `localhost:3307` 기준)
    - `CmsApplication` 실행

접속:
- http://localhost:8080/admin (dev 초기 계정은 회원이 0명일 때 `TestMemberLoader`가 만든다)
- http://localhost:8080/swagger-ui.html (ADMIN 로그인 필요 — 아래 "API Documentation")

---

## DB 스키마 관리 (Flyway)

- 스키마 변경은 **Flyway 마이그레이션 파일로만** 한다 (`src/main/resources/db/migration/`). Hibernate `ddl-auto`는 `validate`로 고정.
- 빈 DB는 기동 시 자동으로 전체 마이그레이션이 적용된다. 기존 DB 전환 절차와 작성 규칙은 [docs/migration-guide.md](docs/migration-guide.md) 참고.

---

## Security

- `/admin/**`, `/admin/api/**` 경로별 접근 제어 (승인 이력은 `com.cms.config`의 `CLAUDE.md` 참고)
- Role(ADMIN/MANAGER) 기반 URL·메서드(`@PreAuthorize`) 이중 접근 제어 + MANAGER 회원별 위임 권한(공지)
- Custom Login Page + 로그인 실패 자동 잠금(5회/30분) + 비밀번호 90일 만료
- CSRF 전 경로 적용, `X-CSRF-TOKEN` 헤더 기반 상태 변경 요청 검증
- 세션 등록·강제 만료(`SessionRegistry` + 이벤트 기반 revoke)
- 무인증 공개 엔드포인트(공지 조회, 비밀번호 재설정) 토큰 버킷 레이트리밋
- actuator는 `/actuator/health`만 공개, 그 외 전부 차단(설정+Security 이중 방어)
- 비밀번호 BCrypt 인코딩, 비밀번호 재설정 토큰 해시(SHA-256) 저장

---

## API Documentation

**dev 프로파일 전용, ADMIN 로그인 필요**(비로그인은 로그인 화면으로 이동). prod는 springdoc이 비활성이라 ADMIN으로 로그인해도 404다 — [docs/deployment.md](docs/deployment.md) "prod에서 잠기는 항목".

Swagger UI:

```
http://localhost:8080/swagger-ui.html
```

OpenAPI JSON:

```
/v3/api-docs
```

---
