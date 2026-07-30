# prod 프로파일 배포 가이드

> 작성일: 2026-07-30 (PLAN-prod-profile.md 구현)

## 범위

이 문서는 `SPRING_PROFILES_ACTIVE=prod`로 앱을 기동해 **배포 가능한 상태를 로컬/서버에서 검증**하는 절차를 다룬다. **실제 인터넷 배포(호스트 선정·도메인·TLS·리버스 프록시)는 이 문서의 범위 밖**이다 — `docker-compose.prod.yml`이 `127.0.0.1:8080`으로 루프백에만 바인딩하는 것도 이 때문이다. 그대로 인터넷에 노출하면 안 된다.

과거(`a8ffb9a` #3) prod 골격이 "운영 서버가 없는데 있는 것처럼 보이는 혼란"을 이유로 제거된 적이 있다 — 이 문서 역시 그 혼란을 만들지 않도록, "배포 가능 상태 검증"과 "실배포"를 명확히 구분한다.

## 사전 준비

1. Docker·Docker Compose가 설치·실행 중이어야 한다.
2. `.env.example`을 `.env.prod`로 복사하고 값을 채운다. **`.env.prod`는 절대 커밋하지 않는다**(`.gitignore`의 `.env*` 규칙으로 이미 차단됨).
3. **시크릿 값에 `$`, `#`, 공백이 포함되면 작은따옴표로 감싼다.** Docker Compose의 `.env` 파일 파싱은 따옴표 없는 값과 큰따옴표 값의 `$`를 보간 대상으로 처리한다 — 작은따옴표만 리터럴로 취급된다.
   ```
   ADMIN_BOOTSTRAP_PASSWORD='P@ss $ w0rd#1'
   ```
4. 기존 DB를 Flyway로 전환하는 경우(신규 환경이 아닌 경우) `docs/migration-guide.md`의 baseline 절차를 먼저 따른다.

## 필수/선택 환경변수

`.env.example` 참고. 필수 값이 비어 있으면 `docker-compose.prod.yml`이 `${VAR:?필수 환경변수입니다}`로 기동 자체를 거부한다.

| 변수 | 필수 여부 | 비고 |
|---|---|---|
| `MYSQL_ROOT_PASSWORD` | 필수 | db 컨테이너 전용. app 컨테이너에는 주입되지 않는다(시크릿 격리) |
| `MYSQL_DATABASE` | 필수 | app의 `DB_URL`이 이 값에서 직접 조합된다 |
| `MYSQL_USER` / `MYSQL_PASSWORD` | 필수 | app의 `DB_USER`/`DB_PASS`가 이 값을 직접 참조한다(별도 키 없음 — 값 drift 방지) |
| `MAIL_USER` / `MAIL_PASS` | 필수 | 비밀번호 재설정 메일 발송용 |
| `APP_BASE_URL` | 필수 | 비밀번호 재설정 메일 링크 생성에 사용. 실제 접속 가능한 URL이어야 한다 |
| `ADMIN_BOOTSTRAP_USER_ID`·`_PASSWORD`·`_EMAIL` | **선택** | 아래 "초기 관리자 계정" 참조 |

## 초기 관리자 계정 (부트스트랩)

`AdminBootstrapLoader`(`@Profile("prod")`)가 기동 시 다음을 판정한다:

- **ACTIVE 상태의 ROLE_ADMIN 계정이 이미 있으면** — 환경변수를 검사하지 않고 그대로 기동한다(정상 운영 환경에서 이 세 변수를 지워도 계속 기동됨).
- **ACTIVE ROLE_ADMIN이 없으면**(빈 DB, 또는 모든 관리자가 비활성/강등된 상태) — `ADMIN_BOOTSTRAP_USER_ID`·`ADMIN_BOOTSTRAP_PASSWORD`·`ADMIN_BOOTSTRAP_EMAIL` 세 변수로 관리자 계정 1개를 생성한다. 세 변수 중 하나라도 없거나 값이 유효하지 않으면(userId 50자 초과, 비밀번호 4자 미만, 이메일 형식 오류 등) **기동을 실패시킨다** — 관리자가 없는 채로 조용히 뜨는 것보다 안전하다는 판단이다.

**부트스트랩 성공 후에는 `.env.prod`에서 `ADMIN_BOOTSTRAP_*` 세 값을 지우고 컨테이너를 재생성하는 것을 권장한다** — 평문 비밀번호가 환경변수 파일에 오래 남아있지 않도록 하기 위함이다. 값을 지운 뒤 재기동해도 이미 ACTIVE ROLE_ADMIN이 있으므로 정상 기동된다.

## 기동

```bash
make prod-up
```

내부적으로 `scripts/prod-up.sh`가 다음을 수행한다:

1. `MYSQL_ROOT_PASSWORD`·`MAIL_PASS`·`ADMIN_BOOTSTRAP_*` 등 Compose가 참조하는 변수가 **호스트 셸에 이미 설정돼 있으면** 경고(값은 출력하지 않음) 후 `unset`한다 — Docker Compose는 호스트 셸 변수를 `--env-file`보다 우선 적용하므로, `.env.prod`만이 유일한 입력이 되도록 강제한다.
2. `docker compose -f docker-compose.prod.yml --env-file .env.prod up -d --build`로 기동한다.
3. 호스트에서 `curl`로 `/actuator/health`를 최대 60초(벽시계 기준) 폴링한다.
4. 60초 안에 200을 받지 못하면 `docker compose logs app`을 출력하고 `docker compose stop app`으로 `restart: unless-stopped`가 만드는 재시작 루프를 멈춘 뒤 비정상 종료(exit 1)한다 — 컨테이너·로그·볼륨은 그대로 남아 사후 분석이 가능하다.

기동 성공 시 `http://127.0.0.1:8080`에서 접속을 검증할 수 있다. **이 바인딩은 검증용이며 그대로 인터넷에 노출하면 안 된다.**

## 중지

```bash
make prod-down
```

`scripts/prod-down.sh`는 `docker compose down`만 실행한다(`-v`/`--volumes` 사용 안 함) — 운영 DB·첨부파일이 담긴 named volume(`cms_db_data_prod`, `cms_notice_attachments_prod`)은 항상 보존된다. 볼륨을 실제로 비우고 싶다면 운영자가 `docker volume rm`을 직접 명시적으로 실행해야 한다(스크립트화하지 않음 — 되돌릴 수 없는 작업을 원클릭으로 만들지 않기 위함).

## 로그

```bash
make logs-prod
```

## prod에서 잠기는 항목

| 항목 | dev | prod |
|---|---|---|
| Swagger UI / API docs | `ROLE_ADMIN` 인증 후 접근 가능 | 완전 비활성(`springdoc.*.enabled=false`) |
| actuator 노출 | `health`만(공통값 상속) | `health`만, `show-details: never` |
| `/actuator/**` (health 제외) | `denyAll()`(공통) | `denyAll()`(공통) |
| `ddl-auto` | `validate`(공통) | `validate`(공통) |
| SQL 로그(`show-sql`) | `true` | `false` |
| 초기 관리자 계정 | `TestMemberLoader`(고정 `admin`/`1234`, 회원 0명일 때만) | `AdminBootstrapLoader`(환경변수 기반, ACTIVE ROLE_ADMIN 없을 때만) |

## 알려진 제약

- `GET /swagger-ui.html`·`/v3/api-docs`는 springdoc 비활성 시 404가 아니라 500을 반환한다(기존 결함 — `docs/troubleshooting.md` "핸들러가 아예 없는 경로가 404가 아니라 500으로 응답됨" 참조). 보안 실질 피해는 없다(문서가 새는 게 아니라 그냥 500).
- 이 문서의 절차는 로컬/서버에서 사람이 직접 실행하는 것을 전제로 한다(`prod-up.sh`는 호스트 `curl`이 필요) — CI에 그대로 재사용할 계획은 없다.
