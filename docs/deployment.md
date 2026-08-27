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

## 백업

```bash
make prod-backup
```

내부적으로 `scripts/prod-backup.sh`가 다음을 수행한다:

1. `mkdir` 기반 잠금(`${TMPDIR:-/tmp}/cms-prod-backup.lock.d`)으로 동시 실행을 막는다 — 이미 실행 중이면 즉시 실패한다.
2. `docker exec cms-db-prod`로 `mariadb-dump --single-transaction --events`를 실행해 DB를 논리 덤프한다(비밀번호는 컨테이너 내부 환경변수로만 참조 — 호스트 프로세스 목록에 노출되지 않는다).
3. `docker run`으로 `cms_notice_attachments_prod` 볼륨(첨부파일 + 프로필 이미지)을 tar로 압축한다.
4. 산출물 4종을 `${BACKUP_DIR:-./backups}/<타임스탬프>-<PID>/`에 남긴다.

| 파일 | 내용 |
|---|---|
| `db.sql.gz` | DB 논리 덤프(gzip 압축) |
| `files.tar.gz` | 첨부파일 + 프로필 이미지 볼륨 전체 |
| `manifest.txt` | 백업 시각·git 커밋·DB명·MariaDB/Flyway 버전 등 메타 정보 |
| `SHA256SUMS` | 위 3개 파일의 체크섬 |

5. `gzip -t`+`tar tzf`+`sha256sum -c`로 즉시 무결성을 검증한다 — 실패하면 해당 백업 디렉터리를 자동 삭제한다.
6. `${BACKUP_RETENTION_DAYS:-14}`일이 지난 백업 디렉터리를 정리한다.

| 환경변수 | 기본값 | 설명 |
|---|---|---|
| `BACKUP_DIR` | `./backups` | 백업 산출물 저장 위치(운영자 셸 변수 — `.env.prod` 소관 아님) |
| `BACKUP_RETENTION_DAYS` | `14` | 이 일수보다 오래된 백업 자동 삭제 |
| `CMS_BACKUP_LOCK_DIR` | `${TMPDIR:-/tmp}/cms-prod-backup.lock.d` | 동시 실행 방지 잠금 디렉터리 |

**범위 한계(굵게 명시)**: 이 백업은 **논리적 오삭제·볼륨 오염으로부터의 로컬 롤백**만을 목표로 한다. **물리 디스크 손상·호스트 전체 유실은 대비하지 못한다** — 기본 `BACKUP_DIR`이 DB 볼륨과 같은 호스트 디스크에 있기 때문이다. 오프사이트/원격 백업은 실배포 호스트가 정해진 뒤의 후속 과제다. 이 도구는 또한 **단일 prod 환경**을 전제로 한다(같은 컨테이너·볼륨 명명 관례를 공유하는 다중 인스턴스 배포는 범위 밖).

**시점 정합성은 약한 보장이다** — DB 덤프를 먼저 뜨고 파일 볼륨을 나중에 압축하므로 두 방향의 불일치가 모두 가능하다: (1) 덤프 이후 새 업로드가 커밋되면 파일만 있고 DB 행이 없는 orphan이 남는다(PII 잔존 가능성 있음, 복구해도 무해 — 그냥 안 쓰이는 파일). (2) **반대로 덤프 이후 첨부 삭제·프로필 이미지 교체가 커밋되면, 덤프에는 옛 storageKey를 참조하는 DB 행이 남아 있는데 정작 파일 압축 시점엔 이미 지워져 있어 tar에 담기지 않는다 — 복구하면 해당 행이 가리키는 파일이 영영 없어 다운로드가 404로 실패한다.** 순서를 반대로 해도 위험이 사라지지 않고 삽입↔삭제 중 어느 쪽이 위험해지는지만 바뀐다(운영 중 삭제보다 업로드가 흔하다는 전제로 현재 순서를 택함). **완전 정합이 필요하면 앱만 정지한 "정지 상태 백업(quiesced backup)"을 쓴다**:

```bash
docker stop cms-app-prod
make prod-backup
docker start cms-app-prod
```

`docker exec cms-db-prod`를 전제로 하므로 **`make prod-down` 후에는 백업이 동작하지 않는다** — DB 컨테이너까지 내려가기 때문이다.

**정기 실행(cron) 예시** (실배포 호스트가 정해진 뒤 등록):
```
0 4 * * * cd /path/to/CMS && make prod-backup >> /var/log/cms-backup.log 2>&1
```

## 복구

`bash scripts/prod-restore.sh <백업디렉터리>`로만 실행한다. **Makefile 타깃은 의도적으로 두지 않는다** — `scripts/prod-down.sh`가 `-v`를 쓰지 않는 것과 같은 원칙("되돌릴 수 없는 작업을 원클릭으로 만들지 않는다")이다.

절차:

1. 백업 무결성 검증(`SHA256SUMS` 형식·체크섬·아카이브 구조)
2. **이중 검증**: manifest에 기록된 DB명이 현재 DB명과 다르면 자동 중단, 백업 SQL의 `USE` 문이 현재 DB명과 다르면(또는 정확히 1개를 찾지 못하면) 자동 중단 — 다른 백업을 잘못 지정하는 실수를 막는다. **이 검증은 단일 Docker 데몬·단일 prod 인스턴스·백업 디렉터리 출처가 신뢰됨을 전제한다** — 다른 호스트의 동일 이름 컨테이너나 위조된 백업까지는 막지 못한다.
3. 실제 DB명을 정확히 타이핑해야 진행되는 대화형 확인(**복구가 성공하면 앱은 복구 전 상태와 무관하게 항상 재기동됨**을 안내)
4. 앱 정지 → 복구 전 상태의 안전 백업 자동 생성 → 대상 볼륨 여유 공간 확인(보수적 추정치, 부족하면 중단) → DB 복구 → 파일 복구(볼륨 내부 스테이징 후 교체) → 재기동 → health/RestartCount 안정성 확인

**재해복구(볼륨이 없는 상태에서 새로 시작)**: 새 prod 스택을 `make prod-up`으로 먼저 올린다(compose가 빈 볼륨을 자동 생성) — 임의의 유효한 새 `.env.prod`면 되고, 원래 백업의 비밀번호와 일치할 필요는 없다. 그 위에 `prod-restore.sh`를 실행한다.

**스테일 잠금 수동 해제**: 비정상 종료로 `$CMS_BACKUP_LOCK_DIR`(기본 `${TMPDIR:-/tmp}/cms-prod-backup.lock.d`)이 남아 이후 백업/복구가 계속 "이미 실행 중"으로 실패하면, `docker ps`·`ps` 등으로 실제로 실행 중인 백업/복구 프로세스가 없는지 확인한 뒤 수동으로 지운다:
```bash
rmdir "${TMPDIR:-/tmp}/cms-prod-backup.lock.d"
```
자동 회수는 하지 않는다 — 스테일 여부 판정을 스크립트가 자동으로 내리는 것 자체가 파괴적 판단의 원클릭화이기 때문이다.

**기존 볼륨 이관(UID 고정 적용 전 이미지로 이미 볼륨을 생성한 경우)**: `Dockerfile`이 `appuser`의 UID·GID를 10001로 고정하기 이전 이미지(`useradd -m appuser`, UID 미지정)로 `cms_notice_attachments_prod` named volume을 이미 생성해둔 상태라면, 새 이미지로 컨테이너만 교체해도 Docker는 기존 volume을 재복사·재소유하지 않는다 — 새 `appuser`(10001)가 옛 UID 소유 파일에 쓰기 실패할 수 있다. 새 이미지로 전환하기 전에 볼륨 소유권을 한 번 맞춰준다:

```bash
MSYS_NO_PATHCONV=1 docker run --rm -v cms_notice_attachments_prod:/target alpine chown -R 10001:10001 /target
```

이 명령은 앱이 정지된 상태에서 실행한다(쓰기 중인 파일과의 경합 방지). `id appuser` 결과가 이미 `uid=10001`이면(신규 배포이거나 이미 이관을 마친 경우) 이 단계는 불필요하다.

## prod에서 잠기는 항목

| 항목 | dev | prod |
|---|---|---|
| Swagger UI / API docs | `ROLE_ADMIN` 인증 후 접근 가능 | 완전 비활성(`springdoc.*.enabled=false`) |
| actuator 노출 | `health`만(공통값 상속) | `health`만, `show-details: never` |
| `/actuator/**` (health 제외) | `denyAll()`(공통) | `denyAll()`(공통) |
| `ddl-auto` | `validate`(공통) | `validate`(공통) |
| SQL 로그(`show-sql`) | `true` | `false` |
| 초기 관리자 계정 | `TestMemberLoader`(고정 `admin`/`1234`, 회원 0명일 때만) | `AdminBootstrapLoader`(환경변수 기반, ACTIVE ROLE_ADMIN 없을 때만) |

## 무인증 공개 엔드포인트 레이트리밋

`cms.rate-limit.*`(전 프로파일 공통값, `application.yml`)이 `/notices/**`·비밀번호 재설정 API를 토큰 버킷으로 방어한다. 상세 설계는 `adversarial-review/plan/PLAN-public-endpoint-rate-limit.md` 참조.

- **운영 튜닝**: `CMS_RATE_LIMIT_ENABLED`(기본 `true`)로 전체를 켜고 끌 수 있다. 개별 규칙의 한도는 `application.yml`을 수정해야 한다(환경변수 인덱스 오버라이드는 지원하지 않음 — Spring Boot relaxed binding은 리스트 프로퍼티의 환경변수 오버라이드를 신뢰하기 어렵다).
- **다중 인스턴스 배포 시 한도가 사실상 배가된다** — 각 인스턴스가 독립된 Caffeine 캐시를 가지므로, 로드밸런서 뒤에 인스턴스 N개를 두면 실질 한도는 설정값의 최대 N배가 된다(현재 단일 인스턴스 전제와 일치, `docker-compose.prod.yml` 변경 없이는 발생하지 않는 시나리오).
- **fail-open 잔여 위험**: 캐시가 포화되는 극단적 상황(대량 IP 회전 공격 등)에서는 개별 IP의 정확한 누적치 보장이 흐트러질 수 있다 — 정확한 유량 계약을 보장하는 게이트웨이가 아니라 "무제한 요청을 값싸게 차단하는 최소 방어"가 목표이기 때문이다. 완전한 정확성이 필요하면 Redis 등 외부 원자적 저장소가 필요하나 이번 범위를 벗어난다.
- nginx 리버스 프록시 도입 시 `server.forward-headers-strategy=native`를 설정하면 레이트리밋의 IP 추출(`request.getRemoteAddr()`) 코드는 변경 없이 실 클라이언트 IP를 기준으로 동작한다 — 단, nginx가 클라이언트 제공 `X-Forwarded-For`를 그대로 통과시키지 않고 자신이 관측한 실제 peer IP로 재작성해야 하고, 애플리케이션 포트(8080)에 외부에서 직접 접근할 수 없어야 한다(로드맵 "실배포 인프라" 항목 범위).

## 알려진 제약

- `GET /swagger-ui.html`·`/v3/api-docs`는 springdoc 비활성 시 404가 아니라 500을 반환한다(기존 결함 — `docs/troubleshooting.md` "핸들러가 아예 없는 경로가 404가 아니라 500으로 응답됨" 참조). 보안 실질 피해는 없다(문서가 새는 게 아니라 그냥 500).
- 이 문서의 절차는 로컬/서버에서 사람이 직접 실행하는 것을 전제로 한다(`prod-up.sh`는 호스트 `curl`이 필요) — CI에 그대로 재사용할 계획은 없다.
- **백업은 오프사이트 보관을 포함하지 않는다** — 같은 호스트 디스크에만 있는 백업은 디스크 전체 손실을 막지 못한다(범위 밖, 후속 과제로 로드맵에 기록 예정).
- **파일 복구가 중단되면 이전 상태·빈 상태·일부만 새 데이터로 교체된 혼합 상태 중 하나로 남을 수 있다** — 볼륨 내부 스테이징 후 최상위 항목 단위로 교체하는 방식이라 완전한 원자성은 아니다. 이 경우 `scripts/prod-restore.sh`의 트랩이 앱을 정지 상태로 유지하고 복구 직전 안전 백업 경로를 안내한다.
- **`_CMS_BACKUP_INTERNAL_CALL` 환경변수를 수동으로 설정하면 잠금·보존 정리를 우회할 수 있다** — 단일 신뢰 운영자가 로컬에서 수동 실행하는 도구라는 위협 모델을 전제로 문서화된 제약으로만 남긴다(직접 설정하지 않는다).
