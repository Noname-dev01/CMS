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
| `MAIL_SMTP_CONNECTION_TIMEOUT_MS`·`_READ_TIMEOUT_MS`·`_WRITE_TIMEOUT_MS` | **선택** | 아래 "SMTP timeout" 참조. 기본값 10000/30000/30000(ms) |
| `APP_BASE_URL` | 필수 | 비밀번호 재설정 메일 링크 생성에 사용. 실제 접속 가능한 URL이어야 한다 |
| `ADMIN_BOOTSTRAP_USER_ID`·`_PASSWORD`·`_EMAIL` | **선택** | 아래 "초기 관리자 계정" 참조 |
| `CMS_CONTENT_IMAGE_MAX_TOTAL_BYTES`·`CMS_CONTENT_IMAGE_MAX_COUNT` | **선택** | 편집기 본문 이미지 전체 상한(DB 등록 이미지 기준). 기본 1GB·10,000개. 아래 "편집기 본문 이미지" 참조 |
| `CMS_INQUIRY_TURNSTILE_ENABLED`·`_SITE_KEY`·`_SECRET_KEY` | **선택** | 공개 문의 폼의 Cloudflare Turnstile 캡차. 기본 `false` = 문의 기능 닫힘(503). 아래 "문의 폼" 참조 |

## SMTP timeout (감사 M-02, remediation-plan.md PR 5)

`application-prod.yml`은 `mail.smtp.connectiontimeout`·`mail.smtp.timeout`(read)·`mail.smtp.writetimeout` 세 socket 단계 timeout을 설정한다 — SMTP 서버가 무응답이어도 비밀번호 재설정 메일 발송 스레드가 무한정 점유되지 않게 하기 위함이다.

- **기본값**: connection 10초 / read 30초 / write 30초. 관측 기반 최종 최적값이 아닌 시작점이며, 필요하면 위 세 환경변수(ms 단위)로 override한다.
- **범위 한정**: 이 세 timeout은 socket 단계만 제한한다. DNS 조회(EHLO용 로컬 호스트명 조회 포함)·짧은 간격으로 계속되는 미완성 응답·메일 발송 밖의 큐/DB 대기는 이 설정으로 해결되지 않는다. `application-dev.yml`은 범위 밖(변경하지 않음) — dev도 동일한 무한 대기 위험이 남는다.
- **값 검증은 수동이다**: `spring.mail.properties`는 `Map<String,String>`이라 Spring이 잘못된 값(단위 문자열·0·음수·비정수)을 기동 시점에 거부하지 않는다 — 잘못된 값이 조용히 무한대(JavaMail 기본값)로 되돌아갈 수 있다. 값을 바꾼 뒤에는 반드시 컨테이너 내부 로그나 진단 코드로 **실제 적용된 `JavaMailSenderImpl` 속성**이 승인된 값과 정확히 일치하는지 확인한다 — `.env.prod`/compose 파일에 적힌 값만으로 적용을 단정하지 않는다.
- **값 변경 시 재생성 필요**: `.env.prod`만 수정하고 기존 컨테이너를 `restart`하는 것으로는 새 환경변수가 반영되지 않는다 — `make prod-up`(내부적으로 `up -d --build`)으로 컨테이너를 재생성해야 한다.

## 초기 관리자 계정 (부트스트랩)

`AdminBootstrapLoader`(`@Profile("prod")`)가 기동 시 다음을 판정한다(D-01 대안 A, 감사 H-01, 2026-09-23 사용자 승인 — `adversarial-review/remediation-plan.md` PR 2 참조):

- **`ROLE_ADMIN` + `ACTIVE`/`LOCKED`/`PASSWORD_EXPIRED` 중 하나인 계정이 이미 있으면** — 환경변수를 검사하지 않고 그대로 기동한다(정상 운영 환경에서 이 세 변수를 지워도 계속 기동됨). 자동·수동 잠금이나 비밀번호 만료로 **지금 당장 로그인은 못 하는** 관리자도 "관리자가 아예 없는 것"과 다르게 취급한다 — 관리자 로그인 차단과 공개 서비스 가용성(재기동 가능 여부)을 분리하는 것이 이 정책의 핵심이다. bootstrap은 이 계정들의 상태·비밀번호 해시·`lockedAt`·`passwordChangedAt`·재설정 토큰 등 어떤 필드도 변경하지 않는다 — 잠금 해제·만료 복귀는 기존 로그인/재설정 요청 경로가 그대로 담당한다.
- **위 적격 상태의 ADMIN이 하나도 없으면**(빈 DB, MANAGER/USER만 존재, 또는 관리자가 전부 `DISABLED`/`DELETED`) — `ADMIN_BOOTSTRAP_USER_ID`·`ADMIN_BOOTSTRAP_PASSWORD`·`ADMIN_BOOTSTRAP_EMAIL` 세 변수로 관리자 계정 1개를 신규 생성한다. 기존 `DISABLED`/`DELETED` 계정을 부활시키거나 덮어쓰지 않는다(별도 신규 ID 필요). 세 변수 중 하나라도 없거나 값이 유효하지 않으면(userId 50자 초과, 비밀번호 15코드포인트 미만·72바이트 초과, 이메일 형식 오류 등) **기동을 실패시킨다** — 관리자가 없는 채로 조용히 뜨는 것보다 안전하다는 판단이다.
- **동시성 범위**: 이 존재 질의와 신규 ADMIN INSERT는 원자적인 한 동작이 아니다 — 보장하는 것은 "질의 실행 시점에 적격 ADMIN이 있었는가"이며, 서로 다른 `ADMIN_BOOTSTRAP_*` 자격증명(다른 userId)을 가진 두 인스턴스가 동시에 최초 기동하면 서로 다른 ADMIN이 각각 생성될 수 있다. **동일 설치의 모든 인스턴스에는 항상 같은 부트스트랩 자격증명을 사용한다.**

**부트스트랩 성공 후에는 `.env.prod`에서 `ADMIN_BOOTSTRAP_*` 세 값을 지우고 컨테이너를 재생성하는 것을 권장한다** — 평문 비밀번호가 환경변수 파일에 오래 남아있지 않도록 하기 위함이다. 값을 지운 뒤 재기동해도 이미 적격 상태의 ROLE_ADMIN이 있으므로 정상 기동된다(로그인 잠금·만료 여부와 무관).

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
| `BACKUP_DIR` | `./backups` | 백업 산출물 저장 위치(운영자 셸 변수 — `.env.prod` 소관 아님). 아래 "정규/보조 백업 모드"에 따라 값을 다르게 지정한다 |
| `BACKUP_RETENTION_DAYS` | `14` | 이 일수보다 오래된 백업 자동 삭제. `BACKUP_DIR`별로 독립 적용된다 |
| `CMS_BACKUP_LOCK_DIR` | `${TMPDIR:-/tmp}/cms-prod-backup.lock.d` | 동시 실행 방지 잠금 디렉터리(모드와 무관하게 항상 공유 — 동시 실행 자체를 막기 위함) |

**범위 한계(굵게 명시)**: 이 백업은 **논리적 오삭제·볼륨 오염으로부터의 로컬 롤백**만을 목표로 한다. **물리 디스크 손상·호스트 전체 유실은 대비하지 못한다** — 기본 `BACKUP_DIR`이 DB 볼륨과 같은 호스트 디스크에 있기 때문이다. 오프사이트/원격 백업은 실배포 호스트가 정해진 뒤의 후속 과제다. 이 도구는 또한 **단일 prod 환경**을 전제로 한다(같은 컨테이너·볼륨 명명 관례를 공유하는 다중 인스턴스 배포는 범위 밖).

### 정규/보조 백업 모드 (감사 M-01·H-04, remediation-plan.md PR 6)

DB 덤프를 먼저 뜨고 파일 볼륨을 나중에 압축하므로 **시점 정합성은 약한 보장이다** — 두 방향의 불일치가 모두 가능하다: (1) 덤프 이후 새 업로드가 커밋되면 파일만 있고 DB 행이 없는 orphan이 남는다(PII 잔존 가능성 있음, 복구해도 무해 — 그냥 안 쓰이는 파일). (2) **반대로 덤프 이후 첨부 삭제·프로필 이미지 교체가 커밋되면, 덤프에는 옛 storageKey를 참조하는 DB 행이 남아 있는데 정작 파일 압축 시점엔 이미 지워져 있어 tar에 담기지 않는다 — 복구하면 해당 행이 가리키는 파일이 영영 없어 다운로드가 404로 실패한다.** 순서를 반대로 해도 위험이 사라지지 않고 삽입↔삭제 중 어느 쪽이 위험해지는지만 바뀐다.

이 시점 불일치를 해소하려면 앱만 정지한 **"정지 상태 백업(quiesced backup)"**이 필요하다 — 이 프로젝트는 현재 규모(단독 운영자, 로컬/검증 단계)에서 quiesced를 **정규(regular) recovery backup**으로 채택하고, 기존 online 백업은 **보조(참고용) 백업**으로 격을 낮춘다. 스크립트 자체(`prod-backup.sh`/`prod-restore.sh`)는 두 모드를 구분하지 않으며 **코드 변경 없이 `BACKUP_DIR` 환경변수만 다르게 지정**해 운용한다.

| 구분 | 보조(Online) | **정규(Quiesced) — 권장** |
|---|---|---|
| 앱 쓰기 | 계속 허용 | 정지 확인 후 실행(진행 중 쓰기 없음) |
| `BACKUP_DIR` | `./backups`(기본값) | `./backups-quiesced` |
| 시점 정합성 | 약한 보장(위 orphan/누락 가능) | DB/파일 동일 시점 보장 |
| 실행 방식 | cron 등 무인 자동 실행 가능 | **운영자가 매일 수동 실행**(자동화 없음 — 이번 범위 밖) |
| 실패 시 | 다음 실행에서 재시도, 기존 유효 백업 유지 | backup 실패를 기록하고 원래 실행 중이던 앱만 재개·health 확인. 원래 정지 상태는 보존. 재개 실패는 별도로 기록하고 정지 확인 후 수동 조치(아래 절차) |
| 정규 복구 수단으로 사용 | **아님** — 참고용일 뿐 | **예** — 실제 복구는 이 백업을 기준으로 판단 |

**실행 전:** 운영자가 daemon/컨테이너·볼륨·출력 경로를 확인하고, 다른 backup/restore·배포·외부 writer가 없는 유지보수 구간을 확보한다. 앱 정지 후에도 다른 writer가 쓰면 정합성을 보장하지 않는다. 아래 블록은 **backup 전용**이며 restore와 함께 실행하지 않는다. 저장소 루트의 Bash에서 블록 전체를 실행하고 출력·종료 코드를 운영 기록에 남긴다. 새 운영 스크립트나 무인 자동화가 아니다.

<!-- quiesced-backup-runbook:start -->
```bash
(
  # 호출 셸의 errexit 때문에 backup 실패 직후 재개 절차가 생략되지 않게 한다.
  # 각 실패는 아래에서 명시적으로 분기하며 호출 셸의 옵션/변수는 바꾸지 않는다.
  set +e
  backup_result=NOT_RUN
  backup_rc=NA
  app_recovery=NOT_ATTEMPTED
  trap 'rc=$?; printf "BACKUP=%s BACKUP_EXIT_CODE=%s APP_RECOVERY=%s\n" "$backup_result" "$backup_rc" "$app_recovery"; exit "$rc"' EXIT
  trap 'app_recovery=INTERRUPTED_MANUAL_CHECK_REQUIRED; exit 130' INT TERM

  app_was_running=$(docker inspect --format '{{.State.Running}}' cms-app-prod) || exit 1
  case "$app_was_running" in true|false) ;; *) echo "앱 상태를 확인할 수 없습니다" >&2; exit 1 ;; esac
  echo "ORIGINAL_APP_RUNNING=$app_was_running"

  if [ "$app_was_running" = true ]; then
    if ! docker stop cms-app-prod >/dev/null; then
      app_recovery=STOP_FAILED_MANUAL_CHECK_REQUIRED
      exit 1 # 정지 결과 불명: 백업 금지, 운영자가 실제 상태를 확인한다.
    fi
  fi
  stopped_state=$(docker inspect --format '{{.State.Running}}' cms-app-prod)
  if [ "$?" -ne 0 ] || [ "$stopped_state" != false ]; then
    app_recovery=STOP_UNCONFIRMED_MANUAL_CHECK_REQUIRED
    exit 1 # 정지가 확인된 경우만 quiesced backup으로 인정한다.
  fi

  if BACKUP_DIR=./backups-quiesced make prod-backup; then
    backup_rc=0
    backup_result=SUCCESS
  else
    backup_rc=$?
    backup_result=FAILED
  fi

  if [ "$app_was_running" = false ]; then
    app_recovery=NOT_REQUIRED_ORIGINALLY_STOPPED
    exit "$backup_rc" # 성공/실패와 무관하게 원래 정지 상태를 보존한다.
  fi

  # backup은 복원이 아니다. backup 실패여도 원래 실행 중이던 앱의 재개는 시도한다.
  # start/health 실패 시 정지 여부까지 확인하고, 확인 불가는 수동 조치 대상으로 남긴다.
  stop_after_recovery_failure() {
    if docker stop cms-app-prod >/dev/null &&
       stopped_state=$(docker inspect --format '{{.State.Running}}' cms-app-prod) &&
       [ "$stopped_state" = false ]; then
      app_recovery=FAILED_STOP_CONFIRMED
    else
      app_recovery=FAILED_STOP_UNCONFIRMED_MANUAL_CHECK_REQUIRED
    fi
  }
  if ! docker start cms-app-prod >/dev/null; then
    stop_after_recovery_failure
    exit 1
  fi
  deadline=$((SECONDS + 60))
  while true; do
    if curl -f -s --connect-timeout 2 --max-time 4 http://127.0.0.1:8080/actuator/health >/dev/null; then
      app_recovery=SUCCESS
      exit "$backup_rc" # health 성공이 backup 실패를 지우지 않는다.
    fi
    if (( SECONDS >= deadline )); then
      stop_after_recovery_failure
      exit 1
    fi
    sleep 3
  done
)
```
<!-- quiesced-backup-runbook:end -->

**결과 판독:** `BACKUP=SUCCESS`만 새 유효 백업으로 기록한다(스크립트의 checksum 검증 포함). `APP_RECOVERY=SUCCESS`는 앱 재개 성공일 뿐 백업 성공이 아니다. backup 실패 후 재개 성공은 **전체 실패(non-zero)**, backup 성공 후 재개 실패도 **전체 실패**지만 이미 생성한 유효 백업은 보존한다. 원래 정지 상태의 `NOT_REQUIRED_ORIGINALLY_STOPPED`는 정상이다. `MANUAL_CHECK_REQUIRED`나 셸/호스트 중단 시에는 담당자가 실제 앱 상태·진행 중인 작업을 확인하고 원래 실행 상태에 맞춰 재개 여부를 판단한다. 정지가 확인되지 않은 상태를 정지 완료로 보고하지 않는다. 시작 시각·원래 상태·종료 코드·두 결과·산출물 경로·중단 시간을 함께 남긴다.

**restore 실패와 구분:** 파괴적 복원 단계 이후의 실패에는 위 재개 규칙을 적용하지 않는다. 기존 restore trap대로 **앱 정지 유지 → 안전 백업 확인 → 수동 재복구 판단**이며 자동 정상 재기동하지 않는다. 성공한 restore가 원래 상태와 무관하게 재기동하는 기존 계약도 backup의 상태 보존 정책과 다르다.

런북 자체의 실패 분기는 `bash scripts/tests/quiesced-runbook-test.sh`로 확인한다. 이 시험은 위 코드 블록을 직접 읽고 Docker/backup/health를 대체하므로 실제 컨테이너·백업을 조작하지 않는다. 실제 복구 훈련(Gate G)을 대신하지 않는다.

`docker exec cms-db-prod`를 전제로 하므로 **`make prod-down` 후에는 백업이 동작하지 않는다** — DB 컨테이너까지 내려가기 때문이다.

**정기 실행(cron) 예시 — 보조(online) 백업 전용, 정규 복구 수단 아님** (실배포 호스트가 정해진 뒤 등록):
```
0 4 * * * cd /path/to/CMS && make prod-backup >> /var/log/cms-backup.log 2>&1
```
이 cron은 `BACKUP_DIR` 기본값(`./backups`)을 그대로 쓰는 **보조** 백업이다. 정규(quiesced) 백업과 혼동하지 않는다 — 둘의 `BACKUP_DIR`이 분리돼 있어 이 cron의 `BACKUP_RETENTION_DAYS` 정리가 정규 백업을 지우지 않는다.

**RPO·RTO·허용 중단 시간(목표 vs 실측, 순수 로컬 학습 단계 전제)**:

- **담당자**: 프로젝트 단독 운영자.
- **RPO 24시간(목표치)**: 호스트와 마지막 유효 quiesced 백업이 모두 생존하는 논리적 오삭제 시나리오를 전제로 한 목표. 수동 실행이 누락되면 실제로는 더 길어질 수 있다 — 매회 마지막 성공 시각을 기록해 실측 확인한다.
- **실제 복구 가능 시점**: 목표치가 아니라 마지막으로 **성공이 확인된** quiesced 백업의 데이터 기준 시점.
- **호스트 전체 유실**: 오프사이트 백업 미포함으로 이 범위에서 복구 보장 없음(아래 "알려진 제약" 참조).
- **RTO(실측)**: 목표를 정하지 않고 `docs/verification/recovery-drill.md`의 drill 실측 소요 시간을 관측치로 기록한다(허용 한도가 아니다).
- **허용 중단 시간**: 앱 정지 시작부터 재기동·health 확인·(복구의 경우) 아래 "복구 후 체크리스트" 완료 후 서비스 재개까지의 전체 구간으로 측정한다.

## 복구

`bash scripts/prod-restore.sh <백업디렉터리>`로만 실행한다. **Makefile 타깃은 의도적으로 두지 않는다** — `scripts/prod-down.sh`가 `-v`를 쓰지 않는 것과 같은 원칙("되돌릴 수 없는 작업을 원클릭으로 만들지 않는다")이다.

절차:

1. 백업 무결성 검증(`SHA256SUMS` 형식·체크섬·아카이브 구조)
2. **이중 검증**: manifest에 기록된 DB명이 현재 DB명과 다르면 자동 중단, 백업 SQL의 `USE` 문이 현재 DB명과 다르면(또는 정확히 1개를 찾지 못하면) 자동 중단 — 다른 백업을 잘못 지정하는 실수를 막는다. **이 검증은 단일 Docker 데몬·단일 prod 인스턴스·백업 디렉터리 출처가 신뢰됨을 전제한다** — 다른 호스트의 동일 이름 컨테이너나 위조된 백업까지는 막지 못한다.
3. 실제 DB명을 정확히 타이핑해야 진행되는 대화형 확인(**복구가 성공하면 앱은 복구 전 상태와 무관하게 항상 재기동됨**을 안내)
4. 앱 정지 → 복구 전 상태의 안전 백업 자동 생성 → 대상 볼륨 여유 공간 확인(보수적 추정치, 부족하면 중단) → DB 복구 → 파일 복구(볼륨 내부 스테이징 후 교체) → 재기동 → health/RestartCount 안정성 확인

**복구 전 안전 백업의 `BACKUP_DIR`**: `prod-restore.sh`가 내부적으로 호출하는 안전 백업(`_CMS_BACKUP_INTERNAL_CALL=1`)은 `BACKUP_DIR`을 인자로 받지 않고 **복구 스크립트를 실행하는 셸의 환경변수를 그대로 물려받는다**. 정규(quiesced) 위치에 안전 백업을 남기고 싶다면 복구 명령 **앞에만** 값을 지정한다 — `export`로 셸에 영구히 남기지 않는다(이후 같은 셸에서 기본 `make prod-backup`을 실행하면 online 백업까지 정규 경로에 섞여 들어간다):

```bash
BACKUP_DIR=./backups-quiesced bash scripts/prod-restore.sh <백업디렉터리>
```

지정하지 않으면 기본값(`./backups`)에 안전 백업이 생성된다.

**복구 후 체크리스트(감사 H-04, 매 실제 운영 복구마다 수행 — 자동화 없음)**: `prod-restore.sh`는 health·`RestartCount` 안정성만 확인하고 종료하며, DB가 참조하는 첨부·프로필 파일이 실제로 존재하는지는 확인하지 않는다. 복구 스크립트 성공 직후, 서비스를 재개하기 전에 운영자가 직접 다음을 확인한다 — **통과 전에는 그 복구 결과를 신뢰하지 않는다**:

1. 복구 대상 DB의 공지 첨부·회원 프로필(`kind=UPLOADED`) storageKey 목록을 조회한다.
2. 목록의 각 파일이 `cms_notice_attachments_prod` 볼륨 안에 실제로 존재하는지 확인한다(대표 표본이 아니라 전수 — 규모가 커지면 스크립트로 자동화하되 이번 범위에서는 수동).
3. 공개 공지 첨부는 실제로 다운로드해 확인하고, 비공개 첨부·프로필은 관리자 로그인 후 조회해 확인한다.
4. 하나라도 실패하면 서비스를 외부/사용자에게 공개하지 않고 안전 백업(위 문단)으로 재복구를 판단한다.

이 체크리스트가 검증하는 설계 자체는 `docs/verification/recovery-drill.md`의 격리 drill로 1회 확인됐다 — drill은 이미지·스키마·스토리지 계약이 바뀔 때마다 재실행한다(계약이 바뀌지 않는 한 매 실제 복구마다 drill 전체를 반복하지는 않는다).

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
| 초기 관리자 계정 | `TestMemberLoader`(고정 `admin`/`1234`, 회원 0명일 때만) | `AdminBootstrapLoader`(환경변수 기반, `ROLE_ADMIN`+`ACTIVE`/`LOCKED`/`PASSWORD_EXPIRED` 중 하나도 없을 때만 — 감사 H-01, 위 "초기 관리자 계정" 절 참조) |

## 정적 페이지 (2026-10-11, adversarial-review/plan/PLAN-static-page.md)

- **새 공개 경로**: `GET·HEAD /pages/{slug}`·`/pages/{slug}/revisions`·`/pages/{slug}/revisions/{no}`가 무인증이다(`SecurityConfig` 승인 2026-10-11, `/pages`·`/pages/**`의 그 외 메서드는 `denyAll`). 공개 메인(`/`) 하단 푸터에는 푸터로 지정한 노출 중 페이지 링크가 붙는다. 리버스 프록시·ingress가 `/pages`를 막고 있다면 확인한다. 레이트리밋 규칙 `public-page`(`/pages/**` 120/60초)가 IP별로 걸린다.
- **저장**: 페이지 메타 `site_page`와 개정 `site_page_revision`은 DB에만 있고 파일 볼륨을 쓰지 않는다(본문 이미지는 지원하지 않는다). 개정은 본문이 바뀐 저장마다 쌓이고 페이지 삭제 때만 함께 지워진다 — **DB 백업(`make prod-backup`)에 두 테이블이 그대로 들어간다**. 개정 한 건은 본문이 최대 약 500KB라 개정이 많이 쌓이면 DB 용량이 늘 수 있다(상한을 두지 않았다 — 요청된 적 없음, 모니터링 포인트).
- **이전 개정은 계속 공개된다**: 노출 중인 페이지의 모든 이전 개정이 `/pages/{slug}/revisions/{no}`로 열린다. 실수로 넣은 민감 정보를 **그 개정만** 가리는 기능은 없고 페이지 비노출(`useYn=false`)·삭제만 수단이다. 비노출이면 현재 버전·이력·이전 개정이 모두 404다.
- **본문 상한**: 정적 페이지 본문은 보이는 글자 50,000자·정리 후 500,000바이트·요청 원문 600,000바이트다(게시글은 10,000자). 리버스 프록시의 요청 본문 크기 제한(`client_max_body_size` 등)이 1MB 미만이면 큰 본문 저장이 프록시에서 막힌다.

## 무인증 공개 엔드포인트 레이트리밋

`cms.rate-limit.*`(전 프로파일 공통값, `application.yml`)이 `/notices/**`·비밀번호 재설정 API를 토큰 버킷으로 방어한다. 상세 설계는 `adversarial-review/plan/PLAN-public-endpoint-rate-limit.md` 참조.

- **운영 튜닝**: `CMS_RATE_LIMIT_ENABLED`(기본 `true`)로 전체를 켜고 끌 수 있다. 개별 규칙의 한도는 `application.yml`을 수정해야 한다(환경변수 인덱스 오버라이드는 지원하지 않음 — Spring Boot relaxed binding은 리스트 프로퍼티의 환경변수 오버라이드를 신뢰하기 어렵다).
- **다중 인스턴스 배포 시 한도가 사실상 배가된다** — 각 인스턴스가 독립된 Caffeine 캐시를 가지므로, 로드밸런서 뒤에 인스턴스 N개를 두면 실질 한도는 설정값의 최대 N배가 된다(현재 단일 인스턴스 전제와 일치, `docker-compose.prod.yml` 변경 없이는 발생하지 않는 시나리오).
- **fail-open 잔여 위험**: 캐시가 포화되는 극단적 상황(대량 IP 회전 공격 등)에서는 개별 IP의 정확한 누적치 보장이 흐트러질 수 있다 — 정확한 유량 계약을 보장하는 게이트웨이가 아니라 "무제한 요청을 값싸게 차단하는 최소 방어"가 목표이기 때문이다. 완전한 정확성이 필요하면 Redis 등 외부 원자적 저장소가 필요하나 이번 범위를 벗어난다.
- nginx 리버스 프록시 도입 시 `server.forward-headers-strategy=native`를 설정하면 레이트리밋의 IP 추출(`request.getRemoteAddr()`) 코드는 변경 없이 실 클라이언트 IP를 기준으로 동작한다 — 단, nginx가 클라이언트 제공 `X-Forwarded-For`를 그대로 통과시키지 않고 자신이 관측한 실제 peer IP로 재작성해야 하고, 애플리케이션 포트(8080)에 외부에서 직접 접근할 수 없어야 한다(아래 "배포 대상(ingress)" 참조).

## 배포 대상(ingress) (감사 M-05, remediation-plan.md PR 6)

**실제 ingress(리버스 프록시·TLS 종료 위치·호스팅)가 아직 정해지지 않았다** — 이 문서·`docker-compose.prod.yml`은 `127.0.0.1:8080` 루프백 바인딩까지만 다루며, 그대로 인터넷에 노출하면 안 된다. ingress topology가 확정되지 않은 상태에서 특정 제품(nginx 등) 설정을 미리 만들지 않는다 — 대신 실제 외부 공개 전에 통과해야 할 체크리스트만 `docs/verification/deployment-edge.md`에 문서화해뒀다.

**클라이언트 IP 소스(체크리스트에서 짚음)**: 레이트리밋(`RateLimitFilter`)과 감사 로그·방문 로그·비밀번호 재설정 로그(`ClientIpResolver`)가 모두 `request.getRemoteAddr()`만 쓴다 — `X-Forwarded-For`/`X-Real-IP` 등 전달 헤더는 신뢰하지 않는다(2026-09-28 `d8952ef` #44, 감사 H-03으로 통일. 그 전에는 감사 로그가 전달 헤더를 검증 없이 우선 사용했다). 리버스 프록시를 도입하면 모든 요청의 `remoteAddr`가 프록시 주소가 되므로, `server.forward-headers-strategy`와 프록시의 헤더 재작성을 함께 구성하고 실제 ingress 경로에서 재확인해야 한다(`ClientIpResolver` 주석도 같은 재검토를 요구한다).

**`docs/verification/deployment-edge.md`의 체크리스트는 전부 "미검증(ingress 미확정)"으로 남아 있다.** 이 문서화 작업의 완료는 로드맵 "후속 과제 — ① 실배포 인프라"(nginx·TLS 인증서·실제 호스팅·CD 파이프라인까지 포함) 항목 자체의 완료를 의미하지 않는다 — 그 항목은 실제 ingress가 구축·검증돼야 완료된다.

## CI 배포 게이트 (감사 M-06, adversarial-review/plan/PLAN-ci-prod-gates.md)

`.github/workflows/ci.yml`의 `prod-smoke` job이 `test` job과 병렬로 다음을 자동 검증한다. 머지 차단은 저장소 브랜치 보호에 `prod-smoke`를 **필수 체크로 등록**해야 성립하며, 2026-09-29에 등록됐다(`["test","prod-smoke"]`, `docs/branching.md`).

- **이미지 참조 검사**(`scripts/ci/check-image-refs.sh`): `mariadb`·`eclipse-temurin` 참조가 전부 digest로 고정돼 있고 mariadb 참조의 sha256이 모두 같은지 확인.
- **스모크**(`scripts/ci/prod-smoke.sh`): 실제 `prod-up.sh`로 이미지 빌드·기동 → health 200 → `/admin/login` 200 → Actuator 대표 3경로(`env`·`beans`·`metrics`)가 무인증 302→`/admin/login`, ADMIN 인증 403 → ADMIN 로그인 후 `/swagger-ui.html`·`/v3/api-docs` 404. 증명 범위는 이 열거 항목뿐이며 `/actuator/**` 전체가 아니다.
- **백업·복구 왕복**(`scripts/ci/prod-backup-restore-roundtrip.sh`): quiesced 백업 → DB 행·볼륨 파일 변경(복구 직전 변경 반영 단언) → 잘못된 DB 이름 입력이 "입력 불일치" 사유로 거절되고 상태 불변 → 실제 복구 → DB 행·sha256·소유권 `10001:10001` 복원, 백업 이후 추가분 소멸. `recovery-drill.md`의 수동 drill(앱 레벨 첨부 조회)을 대체하지 않는다.
- **이미지 스캔**(Trivy, 빌드된 `cms-prod-app`): 수정판 있는 HIGH/CRITICAL이면 실패. OS 레이어와 fat jar 내부 라이브러리를 모두 본다. 예외는 `.trivyignore.yaml`의 `statement`·`expired_at`·`paths`로만 두며 만료되면 다시 실패한다.

**로컬 재현**: 스크립트는 고정 이름의 `cms-*-prod` 컨테이너·`cms_*_prod` 볼륨·`.env.prod`를 만들고 지운다. 폐기 가능한 Docker 환경에서만 실행하며 `CMS_CI_DISPOSABLE_DOCKER=1 bash scripts/ci/prod-smoke.sh`로 시작한다. 기존 `.env.prod`·prod 컨테이너·볼륨이 있거나 127.0.0.1:8080이 사용 중이면 아무 변경 없이 중단한다(dev 스택이 8080을 쓰고 있으면 먼저 정지해야 한다).

### 이미지 digest 갱신

이미지는 `image:tag@sha256:...`(Dockerfile 2, compose 2, `prod-backup.sh`·`prod-restore.sh`)로 고정돼 있고 테스트 컨테이너(`MariaDbContainerSupport`)만 Testcontainers/Spring Boot 이름 검증 제약으로 `mariadb@sha256:...`(태그 없음)를 쓴다. Dependabot(`.github/dependabot.yml`, 운영 방식은 `docs/dependabot.md`)이 Dockerfile·compose의 digest 갱신 PR을 만들지만 **스크립트·Java 리터럴은 추적하지 못한다** — 그 PR에서 나머지 mariadb 참조를 같은 digest로 직접 맞춰야 하며, 어긋나면 `check-image-refs.sh`가 CI를 실패시킨다. 새 digest는 `docker buildx imagetools inspect <image:tag>`로 조회한다.

### 스캔 실패 시 절차

새 CVE가 공개돼 `prod-smoke`가 빨개지면 (1) 수정 버전으로 상향, (2) 불가하면 `.trivyignore.yaml`에 CVE ID·`paths`·사유·책임자·`expired_at`을 적어 예외 처리한다. 예외 없이 우회하려면 필수 체크 설정을 임시 해제하는 방법뿐이며, 그 사실을 PR에 기록한다. Spring Boot BOM이 관리하는 라이브러리는 `build.gradle`의 `ext['tomcat.version']`·`ext['jackson-bom.version']`(Jackson 3)·`ext['jackson-2-bom.version']`(Jackson 2, SpringDoc 경유) 오버라이드로 올리며(Boot가 이 버전 이상을 관리하게 되면 제거), 현재 값과 사유는 해당 파일 주석에 있다.


## 쪽지 서버 로그 비유출의 JDBC·세션 설정 전제 (쪽지, adversarial-review/plan/PLAN-admin-message.md R4-1·R5-1)

쪽지 제목·본문은 다른 회원이 쓴 대화 내용이다. 감사 로그(`admin_action_log`)에는 제목·본문이 없고 실패 `errorMessage`도 고정 문구(`@AdminActionLogged.safeErrorMessage`)지만, Hibernate가 JDBC 예외를 변환하며 서버 로그에 찍는 출력은 코드로 막을 수 없다. 따라서 **서버 로그에 쪽지 내용이 남지 않는다는 보장은 아래 설정이 유지될 때에 한한다** — 운영 `DB_URL`·MariaDB 설정은 코드가 강제하지 않는 외부 값이므로 배포 전 점검한다.

- **Connector/J 진단 옵션 비활성**: `DB_URL`에 `dumpQueriesOnException=true`나 `includeInnodbStatusInDeadlockExceptions=true`가 **없어야 한다**(기본값은 꺼짐). 켜면 SQL(텍스트 프로토콜에서는 값이 치환된 INSERT)·InnoDB 상태가 예외 메시지에 붙어 서버 로그에 제목·본문이 남을 수 있다(교착·FK 진단 때문에 일시적으로 켜더라도 쪽지가 오가는 운영 DB에서는 쓰지 않는다).
- **바인딩·JDBC 쿼리 로깅 비활성**: `org.hibernate.orm.jdbc.bind`(BasicBinder)와 JDBC 쿼리 DEBUG/TRACE 로거를 켜지 않는다. `application-prod.yml`은 `show-sql: false`이고 바인딩 값 로깅 설정이 없다 — 로깅 레벨을 환경변수 등으로 바꿀 때 이 로거를 건드리지 않는다.
- **연결 문자셋·SQL 모드**: 연결이 `utf8mb4`(`character_set_client`)이고 strict SQL 모드(`STRICT_TRANS_TABLES` 또는 `STRICT_ALL_TABLES`)여야 한다. 테이블의 `utf8mb4` 선언만으로 연결 설정이 보장되지 않는다 — `Incorrect string value` 계열 오류는 입력 일부를 오류 메시지에 담을 수 있고, non-strict 모드에서는 오류 대신 값이 조정·경고 처리된다. 확인: `SELECT @@session.character_set_client, @@session.sql_mode;`(앱 연결에서).

시험(`AdminMessageSendIntegrationTest.send_roundTripsFourByteCharacters`·`AdminMessageInstrumentedIntegrationTest`)은 **테스트 연결에서** 문자셋·strict 모드·4바이트 문자 왕복과 로그 비유출(FK 위반·`beforeCommit` 롤백 주입)을 단언한다 — 운영 연결 설정을 대신 증명하지 않는다.

## 권한 운영 주의 (adversarial-review/plan/PLAN-member-permission.md · PLAN-board.md)

MANAGER의 권한은 **회원 단위**다 — 기능 단위 위임은 `member_permission`, 게시판 단위 위임은 `member_board_permission`이고 ADMIN이 `/admin/permission/manage`(권한 관리)에서 준다. 새로 만든 MANAGER는 권한이 없다(대시보드·내 정보만). **기본은 roll-forward(수정 버전 배포)**다.

- **위임 기능이 추가된 버전을 롤백했다가 다시 배포할 때(필수 정리)**: 이전 앱의 권한관리는 자기 카탈로그에 없는 기능 행을 diff에서 제외하므로 롤백 중 "전체 회수"를 해도 그 행이 DB에 남고, 신버전을 다시 배포하면 **회수한 권한이 되살아난다**. 롤백 중의 역할 변경·회원 생성도 이전 앱이 모르는 테이블에는 반영되지 않는다. 재배포는 다음 순서를 지킨다.
  1. **앱 정지**(구버전 인스턴스가 떠 있지 않은지 확인)
  2. 한 트랜잭션으로 정리 — 허용 행이 0개인 회원까지 **전원의 버전을 올려야** 정리 전에 권한 화면을 열어 둔 ADMIN의 오래된 PUT이 과거 권한을 복원하지 못한다:
     ```sql
     START TRANSACTION;
     DELETE FROM member_permission;
     DELETE FROM member_board_permission;
     UPDATE member SET permission_version = permission_version + 1;
     COMMIT;
     ```
  3. **신버전 기동**. 정리 전에 신버전이 먼저 떠서 낡은 행을 캐시에 적재했다면 **앱을 재시작해야 캐시가 폐기된다**(직접 SQL은 캐시 무효화 이벤트를 내지 않고, 이후 같은 집합 PUT은 "변경 없음"이라 무효화하지 않는다).
  4. **확인**: 정리 전에 권한이 있던 MANAGER 계정으로 그 기능의 API가 **실제 403**인지 확인한다(SQL 실행 기록만으로 완료 처리하지 않는다). 이후 ADMIN이 권한관리 화면에서 필요한 매니저에게 다시 부여한다(fail-closed — 정리 직후 MANAGER 전원이 권한 0개).
- **역할 변경과 권한**: ADMIN이 MANAGER의 역할을 바꾸면(승격 등) 그 회원의 기능·게시판 권한 행은 삭제되고 다시 MANAGER로 강등해도 되살아나지 않는다 — 강등 후 권한은 권한관리에서 다시 부여한다.
- **권한 복구·재부여는 권한관리 화면/`PUT`으로만 한다**. 수동 SQL로 넣으면 캐시가 무효화되지 않는다(앱 재시작이 곧 폐기).
- **변형 행 정리**: 권한관리 저장이 "권한 테이블에 형식이 올바르지 않은 행이 있어 저장할 수 없습니다"로 409를 내면 수동 삽입된 대소문자·공백 변형 행(`read`, `'READ '` 등)이 있는 것이다. 판정기는 이런 행을 무시한다. 해당 행을 확인한 뒤 수동 SQL로 정리하고 다시 저장한다(자동 삭제하지 않는다).
- **다중 인스턴스 미지원**: 저장 시 다른 인스턴스의 권한 캐시는 무효화되지 않는다(세션 레지스트리·레이트리밋과 같은 단일 인스턴스 전제).

## 공지 게시판 (2026-10-09, adversarial-review/plan/PLAN-notice-to-board.md)

공지는 `board_key='NOTICE'`인 "공지사항" 게시판(V2 시드, id=1)의 게시글이다. 공개 `/{siteKey}/notices`가 이 게시판에 위임한다.

- **옛 테이블**: 공지 흡수 전 데이터의 동결 스냅샷이던 `notice`·`notice_attachment`는 V3(2026-10-22, `PLAN-legacy-schema-removal.md`)가 지웠다. 공지 첨부는 `post_attachment`다.
- **옛 주소**: `/admin/notice/manage[?id=]`는 공지 게시판의 게시글 관리 화면으로 302한다. `/admin/api/notices/**`는 없다(ADMIN 404, MANAGER 403).

## 공개 메인(`/`)과 배너 (2026-10-10, adversarial-review/plan/PLAN-public-home-banner.md)

- **새 공개 경로**: `GET·HEAD /`(정확 경로 1개)와 `GET·HEAD /banners/{id}/image`가 무인증이다(`SecurityConfig` 승인 2026-10-09). 리버스 프록시·ingress가 `/`를 로그인 리다이렉트로 가정하고 있었다면 확인한다. 레이트리밋 규칙 `public-home`(120/60초)·`banner-image`(240/60초)가 IP별로 걸리며 공유 IP(사내망 등)에서는 한도를 넘으면 이미지가 429로 깨질 수 있다.
- **배너 이미지 저장**: 같은 파일 볼륨의 **`banner/` 하위 디렉터리**(네임스페이스, `profile/`과 같은 방식)에 저장된다. 볼륨 백업·복구는 기존 절차에 그대로 포함된다. 배너 이미지는 업로드 시 파일당 2MB·한 변 2560px 이하, 배너는 최대 10개다.
- **수동 회수(아래 절차) 주의**: ⑤는 `banner/`를 제외하고 **⑤-3이 `content_image` 대조로 따로 회수**한다(비노출·만료 배너 이미지도 보존).
- **세션 관리(2026-10-10, PLAN-session-management.md 쟁점 10)**: `SESSION`은 영구 ADMIN 전용이라 `member_permission`을 쓰지 않는다. 세션 레지스트리는 **단일 인스턴스 메모리**라 앱을 재시작하면 모든 세션이 소멸하고(강제 만료와 무관하게 전원 재로그인) 다중 인스턴스에서는 이 화면이 접속한 인스턴스의 세션만 보여 준다.
- **배너 삭제**: 행을 지운 뒤 커밋 후 파일을 지운다. 파일 삭제가 실패하면 "행 없는 파일"이 남고 ⑤-3이 다음 회수 때 정리한다.

## 게시글 예약 게시 (2026-10-11, adversarial-review/plan/PLAN-post-publish-period.md)

- **동작**: 게시글의 공개 노출은 `useYn ∧ 노출 기간 [publishStart, publishEnd)`이다(시작 포함·종료 미포함, 한쪽이 비면 그쪽은 열림, 둘 다 비면 `useYn`만). 판정은 스케줄러 없이 **조회 시점의 서버 KST `Clock`**으로 한다 — 서버 시계가 틀리면 공개 시각도 틀어진다. 공개 경로 다섯 곳(`/boards` 목록·검색·COUNT, 상세, 첨부 다운로드, 공개 메인의 공지·새 글, 본문 이미지 `/content-images/{id}`)과 `/notices/**`가 모두 같은 조건을 쓴다. 새 공개 경로·`SecurityConfig`·레이트리밋 변경은 없다.
- **스키마**: `post.publish_start`·`publish_end`(`datetime(6) NULL`, NULL = 그쪽 제한 없음). 인덱스는 두지 않았다. 입력은 2000-01-01 이상 2100-01-01 미만, 분 단위로 절단, 오프셋 없는 한국 시각 문자열만 받는다.
- **수용한 한계**: 기간은 통째 교체 + 마지막 저장 우선이다(게시글에 낙관적 락이 없어, 낡은 편집 화면이 다른 관리자가 줄인 기간을 되돌릴 수 있다). 다운로드가 시작된 뒤 종료 시각이 지나도 진행 중 전송은 끝까지 간다(기존 공개 첨부의 약한 보장과 같다).
- **VM·컨테이너 시계**: DB 컨테이너 시계가 UTC여도 앱은 KST `Clock`을 쓰므로 영향이 없다. 다만 SQL로 기간을 직접 넣을 때는 앱 기준 KST 벽시계 값을 리터럴로 쓴다(`NOW()`는 9시간 어긋난다).

## 게시판 표시 설정·쓰기 중지 (2026-10-27, adversarial-review/plan/PLAN-board-definition.md PR 1)

- **동작**: `board`의 새 컬럼 7개(V6)가 공개 목록 크기(10·20·30·50)·조회수 표시·새 글 배지 기간·설명·작성자 표시명·쓰기 허용(`writable_yn`)·변경 버전(`version`)을 담는다. 게시판 PATCH·DELETE는 `version`이 필수이고 낡은 값은 409다(관리 화면은 같은 배포에서 함께 바뀐다).
- **롤백 주의**: V6가 적용된 DB에서 이전 앱으로 되돌려도 기동·동작에는 문제가 없다(모든 새 컬럼이 DEFAULT를 가져 이전 앱의 INSERT가 현행 동작의 기본값을 얻고, `validate`는 남는 컬럼을 문제 삼지 않는다). 롤백 중에는 ① 새 설정이 공개 화면에 반영되지 않고(목록 10건·조회수/배지/설명/표시명 없음) ② **쓰기 중지(`writable_yn = 0`)가 무시되어 보관 게시판에 새 글·수정이 가능**하며 ③ 이전 앱의 게시판 수정은 `version`을 올리지 않는다(재배포 후 낡은 화면 검출이 그 변경분만큼 늦을 뿐 데이터 손상은 없다). 보관 게시판을 롤백 기간에도 지켜야 하면 그 게시판을 `public_yn = 0`으로 돌리거나 권한을 회수한다.
- **쓰기 중지의 범위**: 새 글·수정(노출만 끄는 수정 포함)·첨부 업로드·본문 이미지 업로드가 400이고, 조회·첨부 삭제·게시글 삭제는 된다. 공개 노출은 `public_yn`이 따로 정하므로 보관 게시판도 공개를 유지할 수 있다. 공통 공지 게시판은 쓰기 중지할 수 없다.

## 게시판 작성 정책 (2026-10-28, adversarial-review/plan/PLAN-board-definition.md PR 2)

- **동작**: `board`의 새 컬럼 5개(V7)가 게시판별 첨부 개수(1~5)·파일당 크기(1~10MB)·허용 확장자(전역 허용 목록의 일부, NULL = 전체)·본문 이미지 허용·예약 게시 허용을 담는다. **전역 상한(5개·10MB·확장자 화이트리스트)이 천장**이라 게시판 값은 그 이하로만 줄일 수 있고, DB를 직접 고쳐도 전역 검사가 먼저 막는다. 상한을 낮춰도 이미 넘은 첨부·이미 들어 있는 본문 이미지·이미 설정된 예약 기간은 그대로 두고 **새 입력만** 막는다(본문 이미지를 끈 게시판도 글이 이미 참조 중인 이미지는 유지, 예약을 끈 게시판도 기간 비우기는 허용).
- **롤백 주의**: V7이 적용된 DB에서 이전 앱으로 되돌려도 기동·동작에는 문제가 없다(모든 새 컬럼이 DEFAULT를 가져 이전 앱의 INSERT가 현행 동작의 기본값을 얻고, `validate`는 남는 컬럼을 문제 삼지 않는다). 롤백 중에는 이전 앱이 **게시판별 작성 정책을 무시하고 전역 상한으로만 동작**한다 — 정책보다 큰 첨부, 허용하지 않는 확장자, 새 본문 이미지, 예약 기간이 들어올 수 있다. 재배포 후에도 그 데이터는 그대로 두며 새 입력부터 정책이 다시 적용된다. 롤백 기간에도 지켜야 하는 정책이 있으면 그 게시판의 권한을 잠시 회수한다.

## 게시판 표시 순서·상단 고정 (2026-10-28, adversarial-review/plan/PLAN-board-definition.md PR 3)

- **동작**: `board.sort_order`(0~9999)가 관리 화면(게시판 관리 목록·게시글 화면의 "내 게시판" 선택·권한관리 게시판 표)의 순서를 정하고(작은 값 먼저, 같으면 id 순 — 공개 화면 영향 없음), `board.pin_max_count`(0~5, 0 = 고정 끔)와 `post.pinned`가 공개 목록 1페이지(검색하지 않을 때) 상단의 고정 영역을 만든다. 고정 글은 일반 목록에서도 빠지지 않는다. 고정 개수 = 그 게시판의 미삭제 `pinned` 글 수(노출 여부·기간 무관)이고 상한을 낮추거나 꺼도 이미 고정된 글은 그대로 두며 공개 영역에는 상한만큼만 보인다(해제는 언제나 가능). `post`에 인덱스 `idx_post_board_pinned`가 추가된다.
- **코드 계약(배포와 무관하지만 운영자가 알아 둘 것)**: 게시글 생성·수정(`PostService.createPost`·`updatePost`)이 `READ_COMMITTED` 최상위 트랜잭션 진입점으로 바뀌었다 — 고정 개수 판정을 게시판 `FOR UPDATE` 아래서 직렬화하고 그 잠금 뒤의 개수 읽기가 앞서 커밋된 고정을 보게 하기 위해서다. 고정하는 쓰기 동안에는 같은 게시판의 다른 글 쓰기가 잠시 기다린다.
- **롤백 주의**: V8이 적용된 DB에서 이전 앱으로 되돌려도 기동·동작에는 문제가 없다(모든 새 컬럼이 DEFAULT를 가져 이전 앱의 INSERT가 현행 동작의 기본값을 얻고, `validate`는 남는 컬럼·인덱스를 문제 삼지 않는다). 롤백 중에는 이전 앱이 ① 게시판을 id 순으로 보여 주고 ② 공개 고정 영역을 그리지 않으며 ③ 고정 상한을 모르고(새 고정 자체를 요청할 수 없고) ④ 게시글 수정이 `pinned` 값을 건드리지 않아 고정 상태가 보존된다. 재배포하면 설정과 고정이 그대로 돌아온다. 롤백 중에 만든 게시판·게시글은 DEFAULT(순서 0·고정 끔·고정 아님)다.

## 게시판 슬러그 — 공개 게시판 주소 교체 (2026-10-28, adversarial-review/plan/PLAN-board-definition.md PR 4a)

- **동작**: 공개 게시판 주소가 `/{siteKey}/boards/{슬러그}`로 **완전히 바뀐다** — 슬러그는 기본값 `board-{id}`(저장하지 않음, `board.slug` NULL)이거나 관리자가 한 번 정한 값이다. 숫자 id 주소(`/main/boards/12`·`…/posts/…`·`…/attachments/…`)는 전부 404다. 슬러그를 바꾼 게시판의 옛 주소 `board-{id}`는 새 주소로 **301**(쿼리 유지)이고, 한 번 쓴 슬러그는 게시판을 삭제해도 다시 쓸 수 없다. 외부에 공유된 숫자 주소·검색 색인은 깨진다(실배포 전이라 수용).
- **저장된 링크 치환(V10, Java 마이그레이션)**: 메뉴·배너·팝업 링크와 게시글·정적 페이지(모든 개정)·FAQ·HTML 팝업 본문의 `/{공개 사이트 키}/boards/{id}…`가 `…/boards/board-{id}…`로 바뀐다. 링크가 바뀐 배너는 `version`, 팝업은 `revision`이 1 오른다 — **배포 전에 열어 둔 배너·팝업 편집 화면은 저장이 409가 되고**, 링크가 바뀐 팝업은 방문자의 "오늘 하루 보지 않기"가 한 번 풀린다. 도메인이 붙은 절대 URL(`https://…/main/boards/12`)은 치환하지 않는다(수용한 손실 — 실배포 이후라면 아래 점검 SQL에 우리 도메인 조건을 더해 확인한다).
- **배포 전 점검(이전 버전 DB에서, 기대값 0건)**: V10은 아래 행이 있으면 **실패하고 아무것도 바꾸지 않는다** — 미리 찾아 이전 버전 화면에서 고친다.

  ```sql
  -- 인코딩·점 세그먼트 변형 링크 후보(%62oards처럼 boards를 인코딩해도 잡도록 /boards/ 조건을 붙이지 않는다)
  SELECT 'menu', menu_no, menu_url FROM menu WHERE menu_url LIKE '%\%%' OR menu_url LIKE '%/.%'
  UNION ALL SELECT 'banner', id, link_url FROM banner WHERE link_url LIKE '%\%%' OR link_url LIKE '%/.%'
  UNION ALL SELECT 'popup', id, link_url FROM popup WHERE link_url LIKE '%\%%' OR link_url LIKE '%/.%';
  -- 치환하면 컬럼 길이를 넘을 수 있는 행(링크 하나당 +6자 — 메뉴 255자, 배너·팝업 500자)
  SELECT 'menu', menu_no FROM menu WHERE menu_url LIKE '%/boards/%' AND CHAR_LENGTH(menu_url) > 249
  UNION ALL SELECT 'banner', id FROM banner WHERE link_url LIKE '%/boards/%' AND CHAR_LENGTH(link_url) > 494
  UNION ALL SELECT 'popup', id FROM popup WHERE link_url LIKE '%/boards/%' AND CHAR_LENGTH(link_url) > 494;
  ```

  후보가 나와도 지금 열리지 않는 링크(다른 경로)면 V10은 통과한다 — 정확한 판정은 V10이 하고, 본문 상한(게시글 200,000·정적 페이지 500,000·팝업 50,000·FAQ 30,000바이트)도 V10이 판정한다. **배포 전 DB 백업은 필수**다(사용자 작성 본문을 고치는 되돌릴 수 없는 변환).
- **V10이 실패했을 때**: 업무 데이터는 그대로지만 Flyway가 `success=0` 이력을 남겨 다음 기동을 막는다. 실패 메시지의 `테이블#id`(정적 페이지 개정은 `site_page_revision#{page_id}/{revision_no}`) 행을 이전 버전 앱으로 고치고 → 같은 이미지로 `flyway repair` → 재기동한다(`docs/migration-guide.md` "DDL 실패 복구").
- **배포 후**: 배너·팝업·메뉴 링크와 본문에 숫자 게시판 주소를 새로 넣을 수 없다(배너·팝업·메뉴 저장 400 "게시판 주소는 슬러그 주소…", 본문은 그 링크의 `href`만 지워진다).
- **롤백 = 사실상 불가**: 이전 앱은 `board-12`를 숫자로 읽지 못해 **치환된 링크와 공개 게시판 주소가 전부 404**가 된다. 되돌리려면 V9 이전 백업으로 복구한다(그 사이의 데이터는 잃는다). 롤백 중 만든 게시판은 재배포 후 바로 `board-{id}`로 열리지만, 롤백 중 저장한 숫자 링크는 V10이 다시 돌지 않으므로 남는다 — 재배포 뒤 위 점검 SQL의 `/boards/` 조건으로 찾아 화면에서 고친다(저장하면 400 안내).
- **구·신 앱 혼합 운영 금지**(공개 경로 체계가 다르다 — 위 "공개 경로" 절과 같다).

## 공개 공지 흡수 — `/notices` 상세 주소 종료 (2026-10-28, adversarial-review/plan/PLAN-board-definition.md PR 4b)

- **동작**: 공지는 공지 게시판의 게시글 주소(`/{siteKey}/boards/{공지 게시판 슬러그}/posts/{id}`)로만 열린다. `/{siteKey}/notices`(목록)는 그 사이트의 공지 게시판(전용 → 없으면 공통)으로 **302**(쿼리 유지)하고, 상세·첨부 `/{siteKey}/notices/{id}…`는 **404**다. 외부에 공유된 공지 상세 주소·검색 색인은 깨진다(실배포 전이라 수용). `SecurityConfig` 변경은 없다. 레이트리밋 규칙 `attachment`(`/*/notices/*/attachments/*`)는 지웠다 — 공지 첨부는 `board-attachment` 한도를 받는다.
- **저장된 링크 치환(V11, Java 마이그레이션)**: V10과 같은 대상(메뉴·배너·팝업 링크, 게시글·정적 페이지(모든 개정)·FAQ·HTML 팝업 본문)의 `/{공개 사이트 키}/notices/{id}…`를 공지 게시판의 게시글 주소로 바꾼다 — **그 사이트에서 해석되는 공지 게시판 소속 글만**. 링크가 바뀐 배너 `version`·팝업 `revision`이 1 오른다(배포 전에 열어 둔 편집 화면은 409, 팝업 "오늘 하루 보지 않기"가 한 번 풀림). **4a(V10)와 함께 배포하든 따로 배포하든 V10 → V11 순서로 돈다.**
- **배포 전 점검(이전 버전 DB, 기대값 0건)**: 위 4a 절의 인코딩·점 세그먼트 점검 SQL이 공지 링크도 잡는다(`/boards/` 조건이 없다). 길이 여유는 아래로 본다(링크 하나당 최소 +13자 — 공지 게시판에 사용자 슬러그가 있으면 그 길이 − 7만큼 더).

  ```sql
  SELECT 'menu', menu_no FROM menu WHERE menu_url LIKE '%/notices/%' AND CHAR_LENGTH(menu_url) > 242
  UNION ALL SELECT 'banner', id FROM banner WHERE link_url LIKE '%/notices/%' AND CHAR_LENGTH(link_url) > 487
  UNION ALL SELECT 'popup', id FROM popup WHERE link_url LIKE '%/notices/%' AND CHAR_LENGTH(link_url) > 487;
  ```

  정확한 판정(지금 열리는 링크인지·본문 상한)은 V11이 한다. **배포 전 DB 백업은 필수**다.
- **V11이 실패했을 때**: V10과 같다 — 메시지의 `테이블#id` 행을 이전 버전 앱으로 고치고 → `flyway repair` → 재기동.
- **배포 후 점검**: 치환되지 않은 공지 상세 링크는 지금도(그리고 앞으로도) 404인 링크다(일반 게시판 글·다른 사이트 전용 공지 글·전용 공지가 대체한 공통 공지 글·없는 글). 아래로 찾아 화면에서 고치거나 지운다 — 배너·팝업·메뉴는 그 링크 그대로는 다시 저장되지 않는다(400 "공지 주소는 게시판 주소…"), 본문은 저장·출력 때 그 링크의 `href`만 지워진다.

  ```sql
  SELECT 'menu', menu_no, menu_url FROM menu WHERE menu_url LIKE '%/notices/%'
  UNION ALL SELECT 'banner', id, link_url FROM banner WHERE link_url LIKE '%/notices/%'
  UNION ALL SELECT 'popup', id, link_url FROM popup WHERE link_url LIKE '%/notices/%' OR content_html LIKE '%/notices/%'
  UNION ALL SELECT 'post', id, NULL FROM post WHERE content LIKE '%/notices/%'
  UNION ALL SELECT 'faq', id, NULL FROM faq WHERE answer_html LIKE '%/notices/%';
  ```
- **롤백**: V11은 4a 앱이 이해하는 경로만 만든다 — **4a 앱으로는 롤백할 수 있다**(공지 상세 주소가 다시 열리고 치환된 링크는 게시판 주소로 계속 열린다). 4a 이전 앱으로는 4a 절과 같은 이유로 불가. 롤백 중 저장한 공지 상세 링크는 V11이 다시 돌지 않으므로 재배포 뒤 위 배포 후 점검 SQL로 찾는다.

## 게시글 최종 수정자 (2026-10-27, adversarial-review/plan/PLAN-board-definition.md PR 0)

- **동작**: 게시글의 생성·수정·삭제와 첨부 업로드·삭제가 `post.updater_id`(V5, `varchar(100) NULL`)에 그 관리자의 userId를, `update_date`에 시각을 함께 남긴다. 관리 화면(게시글 목록 "최종 수정자" 열·상세)에만 보이고 공개 화면에는 나가지 않는다. V5 이전에 만들어져 이후 손대지 않은 글은 NULL(`-` 표시 — 수정자 모름)이다. **원본은 감사 로그**(`POST_UPDATE`·`POST_ATTACHMENT_*` 등)이고 이 값은 조회 편의용 사본이다.
- **롤백 주의**: V5가 적용된 DB에서 이전 앱으로 되돌려도 기동·동작에는 문제가 없다(NULL 허용 컬럼, `validate`는 남는 컬럼을 문제 삼지 않는다). 다만 이전 앱은 `updater_id`를 갱신하지 않으므로, 재배포 후 **롤백 기간에 손댄 글은 그 전 수정자가 표시될 수 있다**(이전 앱의 첨부 조작은 `update_date`도 바꾸지 않는다).
- **재배포 전 정리(선택)**: 롤백 시각을 `<T>`(KST 벽시계 값)로 두고, 그 기간에 수정되었거나 첨부가 올라간 글을 "모름"으로 돌린다.

  ```sql
  UPDATE post SET updater_id = NULL
   WHERE update_date >= '<T>'
      OR id IN (SELECT post_id FROM post_attachment WHERE create_date >= '<T>');
  ```

  **부분 복구다** — 롤백 기간의 **첨부 삭제**는 식별할 수 없다(첨부 행이 사라지고, 감사 로그의 `target_id`는 첨부 id라 게시글로 이어지지 않는다). 정확성이 필요하면 `UPDATE post SET updater_id = NULL;`로 전체를 "모름"으로 돌린다(이후 쓰기부터 다시 쌓인다).

## 공개 메인 팝업 (2026-10-12, adversarial-review/plan/PLAN-popup.md)

- **새 공개 경로**: `GET·HEAD /popups/{id}/image`가 무인증이다(`SecurityConfig` 승인 2026-10-12). `/popups`·`/popups/**`의 나머지 경로·메서드는 `denyAll`이다. 리버스 프록시·ingress가 경로를 허용 목록으로 관리한다면 이 경로를 추가한다. 레이트리밋 규칙 `popup-image`(120/60초). 공개 메인(`/`)은 노출 중인 팝업을 등록 순서대로 **최대 5개**까지 그리고, 그 스크립트는 정적 파일 `/js/public/home-popup.js`(기존 `/js/**` 공개 범위)다.
- **팝업 이미지 저장**: 같은 파일 볼륨의 **`popup/` 하위 디렉터리**(네임스페이스, `banner/`·`profile/`과 같은 방식)에 저장된다. 볼륨 백업·복구는 기존 절차에 그대로 포함된다. 이미지형만 파일이 있고 HTML형은 본문이 DB(`popup.content_html`)에만 있다.
- **수동 회수(아래 절차) 주의**: ⑤는 `popup/`도 제외하고 **⑤-4가 `content_image` 대조로 따로 회수**한다(비노출·만료 팝업 이미지도 보존).
- **팝업 삭제**: 행을 지운 뒤 커밋 후 파일을 지운다. 파일 삭제가 실패하면 "행 없는 파일"이 남고 ⑤-4가 다음 회수 때 정리한다.
- **수정과 "오늘 하루 보지 않기"**: 방문자 브라우저의 숨김 상태(`localStorage`)는 서버가 모른다. 팝업을 수정하면 `popup.revision`이 올라가 이전에 숨긴 방문자에게도 다시 표시된다. DB를 직접 고쳐 내용을 바꿀 때는 `revision`도 함께 올려야 같은 효과가 난다.

## FAQ (2026-10-12, adversarial-review/plan/PLAN-faq.md)

- **새 공개 경로**: `GET·HEAD /faq`가 무인증이다(`SecurityConfig` 승인 2026-10-12, **정확 경로 1개**). `/faq`의 그 외 메서드와 `/faq/**` 하위 경로는 `denyAll`이다(비인증은 로그인 302, 인증은 403). 리버스 프록시·ingress가 경로를 허용 목록으로 관리한다면 `/faq`를 추가한다. 레이트리밋 규칙 `public-faq`(120/60초). 노출 중인 카테고리·FAQ만 보이고 답변은 출력 시 한 번 더 정리된다. 공개 스크립트는 정적 파일 `/js/public/faq.js`(기존 `/js/**` 공개 범위)다.
- **파일 저장 없음**: FAQ는 DB 행뿐이고 이미지(답변에 이미지 불가)·첨부가 없어 파일 볼륨·수동 회수 절차에 영향이 없다. 백업·복구는 DB 덤프에 그대로 포함된다.
- **가드 행(`faq_lock`)**: V2가 시드하는 id=1 행이 없으면 FAQ·카테고리 쓰기가 모두 서버 오류가 된다. DB를 수동으로 복구·정리할 때 이 행을 지우지 않는다(지웠다면 `INSERT INTO faq_lock (id, version) VALUES (1, 0);`). `version`은 구조(등록·삭제·카테고리 이동·순서)가 바뀔 때만 올라가므로 **SQL로 FAQ 순서나 소속을 직접 바꿀 때는 `UPDATE faq_lock SET version = version + 1;`도 함께 실행**해야 순서 초안을 만든 열린 관리 화면이 낡았음을 알 수 있다(안 올려도 데이터는 안전하고, 낡은 화면이 409 없이 순서를 덮을 수 있을 뿐이다).
- **상한**: 카테고리 30개·FAQ 200개(초과 시 관리 API 409). 공개 페이지가 노출 중인 FAQ 전체를 한 번에 내려주므로 총량을 묶은 값이다(답변 하나는 정리 후 30KB 이하, 최악 ≈ 6MB).

## 문의 폼 (2026-10-12, adversarial-review/plan/PLAN-inquiry.md)

- **새 공개 경로 — 두 번째 무인증 POST**: `GET·HEAD·POST /inquiry`가 무인증이다(`SecurityConfig` 승인 2026-10-12, **정확 경로 1개**). `/inquiry`의 그 외 메서드와 `/inquiry/**`는 `denyAll`이다(비인증은 로그인 302, 인증은 403). CSRF는 그대로 활성이고 폼 GET이 방문자마다 세션을 만든다. 리버스 프록시·ingress가 경로·메서드를 허용 목록으로 관리한다면 `/inquiry`의 GET·POST를 추가한다. 레이트리밋 `inquiry-submit`(POST, 용량 10 / 3600초)·`public-inquiry`(GET·HEAD, 120/60초) — 키가 `getRemoteAddr()`라 프록시 뒤에서는 모든 방문자가 한 IP로 보이므로 ingress 도입 때 IP 해석(`ClientIpResolver`)을 함께 재검토한다.
- **기본은 닫힘(안전한 기본값)**: `CMS_INQUIRY_TURNSTILE_ENABLED` 기본값이 `false`이고 이때 `GET·POST /inquiry`가 모두 503이다 — 캡차 없이 제출을 받는 상태는 만들 수 없다. 열려면 **`ENABLED=true`와 Cloudflare 대시보드에서 발급한 실제 사이트 키·시크릿 키(`0x4…`)** 가 모두 필요하다. 키가 비었거나 prod에서 Cloudflare **공개 테스트 키**(`1x0…`·`2x0…`·`3x0…`)이면 **앱이 기동 실패**한다(테스트 키를 복사해 넣으면 캡차가 무력화되므로). 세 변수는 `docker-compose.prod.yml`이 명시 매핑하고 `scripts/_prod-env-guard.sh`가 호스트 셸 값을 막는다. 시크릿 값은 `.env.prod`에만 둔다.
- **Cloudflare 의존과 프라이버시**: 폼 페이지가 `challenges.cloudflare.com`의 스크립트를 로드하고, 서버가 방문자 IP와 토큰을 Cloudflare로 보내 검증한다(5초 상한, 실패·타임아웃은 **fail-closed** — Cloudflare 장애 시 문의가 일시 중단된다). **개인정보처리방침에 Cloudflare 전송(위탁/제3자) 고지가 필요하다** — 문구는 운영자가 정적 페이지로 관리한다(법률 판단은 이 문서의 범위 밖).
- **수집 항목과 보존**: 이름·이메일·제목·본문과 동의 시각을 저장한다(방문자 IP·User-Agent는 저장하지 않는다). **스케줄러가 없어 자동 삭제가 없다** — 처리가 끝난 문의는 문의함에서 DELETE 권한자가 직접 삭제한다(하드 삭제가 유일한 파기 수단). 보존 기간은 개인정보처리방침에 맞춰 운영자가 정하고 주기적으로 삭제한다. 미처리(답변 완료가 아닌) 문의가 **1,000건**에 이르면 새 문의를 받지 않는다(503, 소프트 상한). **개인정보가 DB에 있으므로 `prod-backup.sh` 백업에도 포함된다** — 백업 보관·파기 정책도 처리방침과 맞춘다.
- **새 문의 알림**: ADMIN 전원과 `INQUIRY` 조회 권한 MANAGER에게 상수 문장 알림이 간다(문의 내용 없음, 회원당 미읽음 최대 1건).
- **알림 종류를 추가한 버전의 롤백 주의**: `Notification.type`은 `@Enumerated(STRING)`이라, 그 종류를 모르는 이전 앱이 그 행을 읽으면 enum 매핑 예외로 **알림 목록 조회가 실패**한다. 새 알림 종류(예: `INQUIRY_RECEIVED`)를 도입한 버전을 되돌릴 때는 앱 정지 상태에서 `DELETE FROM notification WHERE type = '<새 종류>';`를 먼저 실행한다.

## 사이트 설정 — 사이트 모델과 사이트별 메뉴 (2026-10-13, adversarial-review/plan/PLAN-site-menu.md PR ①)

- **모델**: `site` 테이블(V2 시드 — 관리자 사이트 `id=1`·기본 공개 `main` `id=2`)과 `menu.site_id`(사이트마다 메뉴 트리). 사이트는 사이트 설정 화면(`/admin/site/manage`, ADMIN 전용)에서, 메뉴는 메뉴 관리 화면에서 편집할 사이트를 골라 관리한다.
- **운영 메모**: 메뉴·사이트 쓰기는 사이트 행을 `FOR UPDATE`로 먼저 잠그고 `READ_COMMITTED`로 실행한다 — 같은 사이트의 메뉴 편집은 직렬화되고 서로 다른 사이트는 병렬이다. 사이트 키는 공개 경로 접두어가 될 값이라 예약어(`SiteKey.RESERVED`)를 쓸 수 없고 생성 후 바꿀 수 없다.

## 공개 경로 `/{siteKey}/…` (2026-10-13, adversarial-review/plan/PLAN-site-menu.md PR ②)

- **경로**: 공개 화면은 `/{siteKey}/…` 아래에 있다 — 공지 `/main/notices`, 게시판 `/main/boards/{id}`, 정적 페이지 `/main/pages/{slug}`, FAQ `/main/faq`, 문의 `/main/inquiry`, 공개 메인 `/main`. **`/`는 기본 공개 사이트로 302**하고 **접두어 없는 옛 URL(`/notices` 등)은 404로 종료**된다. 이미지(`/banners/{id}/image`·`/popups/{id}/image`·`/content-images/{id}`)와 정적 리소스는 사이트 무관 경로다. 기본 사이트는 `site.default_yn`이 표시하고 시드 `main`이 기본이다.
- **인가 정책 변경(2026-10-13 승인)**: `SecurityConfig`에 사이트 범위 무인증 경로가 생겼고 옛 permit 5묶음은 "GET·HEAD 열림 → 404" 종료 규칙으로 바뀌었다. 레이트리밋 패턴이 `/*/…` 접두어를 포함하고 사이트 홈 규칙 `public-site-home`이 추가됐다. 점 없는 임의의 단일 세그먼트 `GET /xxx`가 비인증 302 대신 404를 돌려준다. 표는 `com.cms.config`의 `CLAUDE.md`.
- **링크 입력**: 배너·팝업·메뉴의 내부 링크는 `/main/boards/2`처럼 사이트 접두어를 붙여 저장한다(접두어 없는 경로는 404). 로드밸런서·모니터링의 헬스체크는 `/actuator/health`다.
- **혼합 버전 운영 금지**: 공개 경로 체계가 다른 앱 버전을 섞으면 URL의 성공 여부가 인스턴스마다 달라지므로 롤링·블루그린 겹침 없이 **일괄 교체**한다.
- **기본 사이트 운영**: 관리자 사이트 탭에서 [기본으로 지정]으로 바꾼다(ADMIN 전용). 기본 표시가 정확히 1개의 활성 공개 사이트가 아니면 `/`는 404(오류 로그 `기본 공개 사이트 표시가 정확히 1개가 아닙니다`)이므로 DB를 직접 고치지 않는다.
- **새 사이트 = 새 무인증 경로**: 활성 공개 사이트를 만들면 즉시 `/{siteKey}/…`가 공개된다. 콘텐츠(배너·팝업·FAQ·정적 페이지·푸터)는 PR ③부터 `site_id`로 사이트마다 분리되고(공통은 모든 사이트, 아래 "사이트별 콘텐츠") 게시판·공지도 PR ④부터 `board.site_id`로 사이트마다 분리된다(아래 "사이트별 게시판·공지") — 의도하지 않은 사이트는 비활성화한다.

## 사이트별 콘텐츠 (2026-10-13, adversarial-review/plan/PLAN-site-menu.md PR ③)

- **모델**: 배너·팝업·FAQ 카테고리·정적 페이지에 소속 사이트 `site_id`(NULL = 모든 공개 사이트 공통, FK `ON DELETE RESTRICT`)가 있다. 관리자가 관리 화면의 "표시 대상"에서 사이트를 고르면 그 사이트에서만 보인다. 배너·FAQ 카테고리에는 낡은 화면 검사용 `version`(기본 1)이 있다. **`site_id`를 NULL로 바꾸면 모든 사이트에 공개된다** — 전용 콘텐츠를 숨기려면 비노출·삭제한다.
- **관리 API 계약**: 배너·팝업·FAQ 카테고리·정적 페이지의 수정(PUT)에 `siteScope`(`COMMON` 또는 사이트 ID)가 **필수**이고, 배너·FAQ 카테고리는 `version`, 팝업은 `revision`(시작값 0), FAQ **항목** 수정은 `baseCategoryId`도 필수다(없으면 400, 낡은 값이면 409). 등록(POST)은 `siteScope`를 생략하면 공통이다.
- **사이트 삭제**: 배너·팝업·FAQ 카테고리·정적 페이지 중 하나라도 그 사이트 전용이면 삭제할 수 없다(409, FK도 `RESTRICT`). 비활성화하면 전용 콘텐츠가 모든 공개 경로(이미지 id 경로 포함)에서 사라진다.
- **알려진 한계**: 정적 페이지 슬러그는 사이트 간에 유일하다(같은 `privacy`를 사이트마다 둘 수 없다). 배너 10·팝업 30·FAQ 카테고리 30·FAQ 200 상한은 사이트별이 아니라 전체 합계다. 위임받은 MANAGER(`BANNER`·`POPUP`·`FAQ`·`SITE_PAGE`)는 모든 표시 대상을 지정할 수 있다(사이트별 위임 없음). 이미지 URL(`/banners/{id}/image` 등)은 사이트 맥락이 없어 노출 중이면 다른 사이트 접두어 아래에서도 id로 열린다.

## 사이트별 게시판·공지 (2026-10-14, adversarial-review/plan/PLAN-site-menu.md PR ④)

- **모델**: 게시판에 소속 사이트 `board.site_id`(NULL = 모든 공개 사이트 공통, FK `ON DELETE RESTRICT`)가 있다. 시드 공지 게시판은 공통이다. 관리자가 게시판 관리 화면의 "표시 대상"에서 사이트를 고르면 그 게시판은 그 사이트의 `/{siteKey}/boards/{id}`·메인 "새 글"에만 나온다(다른 사이트에서는 없는 ID와 같은 404). **사이트 공지 게시판**을 만들면(게시판 만들기에서 사이트를 고르고 "이 사이트의 공지 게시판으로 만들기") 그 사이트의 `/{siteKey}/notices`와 메인 "공지" 섹션이 **공통 공지 대신 이 게시판**을 보여 준다(사이트마다 하나, 키 `NOTICE:{siteId}`). 전용 공지 게시판이 없는 사이트는 공통 공지를 본다.
- **관리 API 계약**: 게시판 생성의 선택 필드 `siteScope`(`COMMON` 또는 사이트 ID, 누락 = 공통)·`noticeBoard`. 수정(PATCH)은 `siteScope`를 **명시한 경우에만** 대상을 바꾼다(누락 = 유지, 공지 게시판은 변경 400). 응답에 `siteId`·`noticeBoard`가 있다.
- **삭제 규칙**: 공통 공지 게시판은 계속 삭제할 수 없다(409). **사이트 전용 공지 게시판은 살아 있는 글이 없으면 삭제할 수 있다** — 삭제하면 그 사이트는 공통 공지로 돌아간다(막으면 사이트를 지울 수 없다). 게시판을 삭제(소프트)하면 `site_id`와(전용 공지의) 키가 비워진다.
- **사이트 삭제**: 배너·팝업·FAQ 카테고리·정적 페이지에 더해 **게시판**이 그 사이트 전용이면 삭제할 수 없다(409, FK도 `RESTRICT`).
- **알려진 한계**: 본문 이미지(`/content-images/{id}`)는 사이트 맥락이 없어 공개 글이 참조하면 다른 사이트 접두어 아래에서도 id로 열린다(소속 사이트가 비활성이면 404). 전용 공지 게시판은 공통 공지와 합쳐 보이지 않고 **대체**한다. 게시글 관리 화면의 "내 게시판" 선택에는 사이트 표시가 없다(이름과 `#id`로 구분). 옛 관리자 주소 `/admin/notice/manage`는 공통 공지 게시판으로 간다.

## 편집기 본문 이미지 (2026-10-07, adversarial-review/plan/PLAN-html-editor.md)

공지·게시판 편집기에서 올린 이미지는 첨부파일(공지 첨부·게시글 첨부)과 같은 파일 볼륨(`notice_attachments_*`)의 루트에 저장되고, `content_image` 행·`content_image_ref`(어느 소유자가 참조하는지 — 게시글 `POST`·배너 `BANNER`·팝업 `POPUP`)·`content_image_usage`(전체 바이트·개수 카운터)로 관리된다. 공개 게시판(공지 게시판 포함)의 공개 게시글이 참조하는 이미지만 `/content-images/{id}`로 무인증 공개된다.

- **업로드 출처(2026-10-08, adversarial-review/plan/PLAN-board.md 쟁점 9)**: 이미지 행에 올린 곳(`scope_type` = 게시판 `BOARD` + `scope_id`, 또는 `LIBRARY`·`BANNER`·`POPUP`)이 기록된다. 게시글 본문에는 같은 게시판 출처 이미지(또는 라이브러리 계열 이미지)만 넣을 수 있다(다른 출처면 저장 400). 공지도 공지 게시판의 게시글이라 `BOARD`+공지 게시판 출처다(`scope_type`은 기본값이 없어 앱이 항상 값을 넣어야 한다 — 옛 기본값 `NOTICE`는 V3가 없앴다).
- **게시글 첨부·참조(2026-10-08, adversarial-review/plan/PLAN-board.md 쟁점 7)**: 게시글 첨부(`post_attachment`)도 본문 이미지와 같은 **스토리지 루트**에 저장된다(네임스페이스가 아님). 그래서 아래 회수 절차의 ② 대상 확정은 **살아 있는 게시글(`post.deleted=0`)이 참조하는 이미지를 제외**하고, ⑤ 행 없는 파일 정리는 **`post_attachment`의 키를 보존 목록에 포함**한다 — 이 두 조건이 빠진 절차를 쓰면 게시글 본문 이미지·첨부 파일이 지워진다.
- **상한 도달(업로드 409)**: 저장하지 않은 편집 중 이미지·본문에서 지운 이미지·삭제된 게시글의 이미지는 **자동으로 정리되지 않는다**. 다만 2026-10-12부터 ADMIN이 **미디어 라이브러리**(`/admin/media/manage`)에서 출처 "게시판 본문" + "사용 안 함만" 필터로 이런 이미지를 직접 지울 수 있다(사용 중이면 삭제가 409로 막히고 사용처가 보인다) — 아래 수동 회수는 앱을 정지하고 일괄로 정리해야 할 때 쓴다. 상한(전체 1GB·1만 개)은 배너·팝업·라이브러리 이미지까지 **하나의 카운터**로 센다.

### 수동 회수 절차 (dev에서 2026-10-07 실제 수행 검증)

**반드시 앱을 정지한 유지보수 구간에서 한다** — 앱이 떠 있으면 대상을 조회한 직후 관리자가 그 이미지를 본문에 다시 넣어 저장할 수 있어 사용 중인 파일을 지우게 된다. 아래는 prod 이름(`cms-db-prod`·`cms_notice_attachments_prod`) 기준이다(dev는 `cms-db-dev`·`cms_notice_attachments_dev`). Git Bash에서는 `MSYS_NO_PATHCONV=1`을 붙인다.

```bash
# DB 자격 증명은 DB 컨테이너 내부 환경변수로만 참조한다 — .env.prod는 compose 보간용이라 호스트 셸에는 없다.
# SQL은 stdin으로 넘기며, 접속·인증·쿼리 실패는 0이 아닌 종료 코드가 된다.
db() { docker exec -i cms-db-prod sh -c 'exec mariadb -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" -N'; }
# 삭제 단계 직전 가드: ① 백업 완료 표지가 있고 앱이 정지 상태일 때만 통과한다.
# (아래 ②·⑤가 게시글 참조·첨부를 보존한다, adversarial-review/plan/PLAN-board.md 리뷰 R4-1)
ready() {
  [ -f reclaim-backup.ok ] && [ "$(docker inspect -f '{{.State.Running}}' cms-app-prod)" = false ] ||
  { echo "중단: ① 백업 미완료이거나 앱이 실행 중이다"; return 1; }
}
# 이 블록의 삭제 단계는 모두 && 체인이다 — 서브셸 + set -e로 바꾸지 않는다(`( set -e; … ) || …`처럼
# || 목록 안에서는 bash가 set -e를 무시해 실패 뒤에도 다음 명령이 실행된다)

# ① 앱 정지 + 백업 — 둘 다 성공해야 표지 파일이 생긴다
rm -f reclaim-backup.ok
docker stop cms-app-prod && make prod-backup && touch reclaim-backup.ok

# ② 회수 대상 확정(미디어 라이브러리 도입 후 개정 — PLAN-media-library.md R1-7): **살아 있는 어떤 소유자도 참조하지 않는** 이미지.
#    소유자 = 삭제되지 않은 게시글(POST — 노출 여부·게시판 공개 여부와 무관, 비노출·비공개 게시판 글의 이미지도 운영자가 다시 켜면 쓰이므로 회수하지 않는다),
#    존재하는 배너(BANNER)·팝업(POPUP). 배너·팝업이 직접 올렸다가 소유자가 사라진 이미지(BANNER/POPUP 출처)도 대상이다.
#    **라이브러리 직접 업로드(scope_type='LIBRARY')는 일부러 참조 없이 쌓아 두는 자산이라 대상에서 제외한다** — 라이브러리 화면에서 직접 지운다.
#    공지는 공지 게시판의 게시글이라 참조도 게시글 참조(POST)다 — 별도 NOTICE 조건이 없다.
#    출력은 "id <탭> 스토리지 루트 기준 상대 경로"다 — 루트 영역(namespace='')은 키 그대로, banner/·popup/은 접두어를 붙인다(④가 이 경로를 지운다).
db > reclaim-targets.tsv <<'SQL'
SELECT i.id, CONCAT(IF(i.namespace = '', '', CONCAT(i.namespace, '/')), i.storage_key) FROM content_image i
WHERE i.scope_type <> 'LIBRARY'
  AND NOT EXISTS (SELECT 1 FROM content_image_ref r JOIN post p ON r.owner_type = 'POST' AND p.id = r.owner_id
                  WHERE r.image_id = i.id AND p.deleted = 0)
  AND NOT EXISTS (SELECT 1 FROM content_image_ref r JOIN banner b ON r.owner_type = 'BANNER' AND b.id = r.owner_id
                  WHERE r.image_id = i.id)
  AND NOT EXISTS (SELECT 1 FROM content_image_ref r JOIN popup pp ON r.owner_type = 'POPUP' AND pp.id = r.owner_id
                  WHERE r.image_id = i.id)
ORDER BY i.id;
SQL
IDS=$(cut -f1 reclaim-targets.tsv | paste -sd, -)   # 비어 있으면(조회 실패 포함) ③의 SQL이 실패해 ④까지 건너뛴다

# ③ 한 트랜잭션: 참조 → 이미지 행 삭제 → 카운터 재계산
# ④ DB 커밋이 성공한 뒤에만 파일 삭제(②의 목록 — 경로 탈출 형태는 건너뜀). 롤백됐는데 파일을 지우면 행만 남는다
ready &&
db <<SQL &&
START TRANSACTION;
DELETE FROM content_image_ref WHERE image_id IN ($IDS);
DELETE FROM content_image WHERE id IN ($IDS);
UPDATE content_image_usage SET total_bytes = (SELECT COALESCE(SUM(file_size), 0) FROM content_image),
       total_count = (SELECT COUNT(*) FROM content_image) WHERE id = 1;
COMMIT;
SQL
cut -f2 reclaim-targets.tsv > reclaim-keys.txt &&
docker run --rm -v cms_notice_attachments_prod:/data -v "$PWD":/work alpine:3.20 sh -c '
  while IFS= read -r k; do case "$k" in ""|/*|*..*) echo "skip: $k";; *) rm -f -- "/data/$k";; esac; done < /work/reclaim-keys.txt' ||
echo "③④ 중단: 가드 실패·대상 없음·DB 정리 실패 — 파일을 지우지 않았다"

# ⑤ 행 없는 파일 정리: 업로드 중 강제 종료·롤백 시 삭제 실패로 남은 파일(카운터에 잡히지 않음).
#    보존 대상 = **루트 영역(namespace='')** content_image + post_attachment의 storage_key, profile/·banner/·popup/ 네임스페이스는 제외(⑤-2·⑤-3·⑤-4가 따로 다룬다 — 제외하지 않으면 정상 배너·팝업 이미지가 고아로 분류돼 삭제된다, PLAN-public-home-banner.md R4-1·PLAN-popup.md R3-1).
#    banner/·popup/ 네임스페이스의 content_image 행은 보존 목록에 넣지 않는다 — 같은 키 문자열이 루트의 고아 파일을 우연히 보존하게 만들 수 있다(PLAN-media-library.md R4-1).
#    공지 첨부는 post_attachment다(옛 notice_attachment 테이블은 V3가 지웠다).
#    보존 목록 조회가 실패해 빈 목록이 되면 사용 중인 파일까지 오펀으로 분류된다 — 어느 단계든 실패하면
#    orphan-files.txt가 만들어지지 않고, 아래 삭제는 그 파일이 없으면 아무것도 지우지 않는다.
rm -f keep-keys.txt disk-keys.txt orphan-files.txt
ready &&
db > keep-keys.txt <<'SQL' &&
SELECT storage_key FROM content_image WHERE namespace = '' UNION SELECT storage_key FROM post_attachment;
SQL
sort -o keep-keys.txt keep-keys.txt &&
docker run --rm -v cms_notice_attachments_prod:/data alpine:3.20 sh -c '
  cd /data && find . -type f -not -path "./profile/*" -not -path "./banner/*" -not -path "./popup/*" -not -path "./.restore-staging/*" | sed "s|^\./||" | sort' > disk-keys.txt &&
comm -23 disk-keys.txt keep-keys.txt > orphan-files.txt ||
echo "⑤ 중단: 보존 목록 또는 디스크 목록 수집 실패 — 아래 삭제를 실행하지 않는다"
cat orphan-files.txt    # 먼저 눈으로 확인한다(파일이 없다는 오류가 나오면 위 단계가 실패한 것)
ready && docker run --rm -v cms_notice_attachments_prod:/data -v "$PWD":/work alpine:3.20 sh -c '
  test -f /work/orphan-files.txt || { echo "orphan-files.txt 없음 — 삭제 중단"; exit 1; }
  while IFS= read -r k; do case "$k" in ""|/*|*..*) ;; *) rm -f -- "/data/$k";; esac; done < /work/orphan-files.txt'

# ⑤-2 프로필 파일 대조(2026-10-08, PLAN-board.md R6-1): 트랜잭션 결과를 알 수 없을 때(커밋 응답 유실 등) 업로드 정리는 파일을 지우지 않고 남긴다.
#      프로필 이미지는 profile/ 네임스페이스에 저장되어 위 ⑤가 제외하므로, 여기서 따로 대조한다.
#      보존 대상 = member의 profile_image_kind='UPLOADED' 행의 profile_image_url(네임스페이스 로컬 키), 디스크 = profile/ 아래 파일(접두어 제거).
#      차이만 고아 목록이 된다. 앞 단계와 같은 ready 가드 + && 체인 — 조회가 실패하면 목록이 만들어지지 않고 아래 삭제는 아무것도 지우지 않는다.
rm -f profile-keep.txt profile-disk.txt profile-orphans.txt
ready &&
db > profile-keep.txt <<'SQL' &&
SELECT profile_image_url FROM member WHERE profile_image_kind = 'UPLOADED' AND profile_image_url IS NOT NULL;
SQL
sort -o profile-keep.txt profile-keep.txt &&
docker run --rm -v cms_notice_attachments_prod:/data alpine:3.20 sh -c '
  if [ -d /data/profile ]; then cd /data/profile && find . -type f | sed "s|^\./||" | sort; fi' > profile-disk.txt &&
comm -23 profile-disk.txt profile-keep.txt > profile-orphans.txt ||
echo "⑤-2 중단: 보존 목록 또는 디스크 목록 수집 실패 — 아래 삭제를 실행하지 않는다"
cat profile-orphans.txt    # 먼저 눈으로 확인한다(파일이 없다는 오류가 나오면 위 단계가 실패한 것)
ready && docker run --rm -v cms_notice_attachments_prod:/data -v "$PWD":/work alpine:3.20 sh -c '
  test -f /work/profile-orphans.txt || { echo "profile-orphans.txt 없음 — 삭제 중단"; exit 1; }
  while IFS= read -r k; do case "$k" in ""|/*|*..*) ;; *) rm -f -- "/data/profile/$k";; esac; done < /work/profile-orphans.txt'

# ⑤-3 배너 파일 대조(2026-10-10, PLAN-public-home-banner.md R4-1 — 일회용 스키마·볼륨에서 이 블록을 그대로 실행해 비노출·만료 배너 파일 보존과 행 없는 파일만 삭제됨을 확인): 배너 이미지는 banner/ 네임스페이스에 저장되어 위 ⑤가 제외하므로 여기서 따로 대조한다.
#      보존 대상 = content_image의 namespace='banner' 행의 storage_key(네임스페이스 로컬 키 — 배너와 이미지의 연결은 content_image_ref 한 곳이고 옛 banner.storage_key 컬럼은 V3가 지웠다).
#      **비노출·만료 배너도, 배너가 지워졌어도 게시글이 아직 참조하는 이미지도 행이 있으면 보존**), 디스크 = banner/ 아래 파일(접두어 제거).
#      차이만 고아 목록이 된다. 앞 단계와 같은 ready 가드 + && 체인 — 조회가 실패하면 목록이 만들어지지 않고 아래 삭제는 아무것도 지우지 않는다.
rm -f banner-keep.txt banner-disk.txt banner-orphans.txt
ready &&
db > banner-keep.txt <<'SQL' &&
SELECT storage_key FROM content_image WHERE namespace = 'banner';
SQL
sort -o banner-keep.txt banner-keep.txt &&
docker run --rm -v cms_notice_attachments_prod:/data alpine:3.20 sh -c '
  if [ -d /data/banner ]; then cd /data/banner && find . -type f | sed "s|^\./||" | sort; fi' > banner-disk.txt &&
comm -23 banner-disk.txt banner-keep.txt > banner-orphans.txt ||
echo "⑤-3 중단: 보존 목록 또는 디스크 목록 수집 실패 — 아래 삭제를 실행하지 않는다"
cat banner-orphans.txt    # 먼저 눈으로 확인한다(파일이 없다는 오류가 나오면 위 단계가 실패한 것)
ready && docker run --rm -v cms_notice_attachments_prod:/data -v "$PWD":/work alpine:3.20 sh -c '
  test -f /work/banner-orphans.txt || { echo "banner-orphans.txt 없음 — 삭제 중단"; exit 1; }
  while IFS= read -r k; do case "$k" in ""|/*|*..*) ;; *) rm -f -- "/data/banner/$k";; esac; done < /work/banner-orphans.txt'

# ⑤-4 팝업 파일 대조(2026-10-12, PLAN-popup.md R3-1 — 일회용 MariaDB·볼륨에서 이 블록 전체를 실행해 활성·비노출·만료 팝업 파일 보존과 행 없는 파일만 삭제됨을 확인): 팝업 이미지는 popup/ 네임스페이스에 저장되어 위 ⑤가 제외하므로 여기서 따로 대조한다.
#      보존 대상 = content_image의 namespace='popup' 행의 storage_key(이미지형만 이미지가 있다 — **비노출·만료 팝업도, 팝업이 지워졌어도 게시글이 아직 참조하는 이미지도 행이 있으면 보존**, HTML형은 이미지가 없다), 디스크 = popup/ 아래 파일(접두어 제거).
#      차이만 고아 목록이 된다. 앞 단계와 같은 ready 가드 + && 체인 — 조회가 실패하면 목록이 만들어지지 않고 아래 삭제는 아무것도 지우지 않는다.
rm -f popup-keep.txt popup-disk.txt popup-orphans.txt
ready &&
db > popup-keep.txt <<'SQL' &&
SELECT storage_key FROM content_image WHERE namespace = 'popup';
SQL
sort -o popup-keep.txt popup-keep.txt &&
docker run --rm -v cms_notice_attachments_prod:/data alpine:3.20 sh -c '
  if [ -d /data/popup ]; then cd /data/popup && find . -type f | sed "s|^\./||" | sort; fi' > popup-disk.txt &&
comm -23 popup-disk.txt popup-keep.txt > popup-orphans.txt ||
echo "⑤-4 중단: 보존 목록 또는 디스크 목록 수집 실패 — 아래 삭제를 실행하지 않는다"
cat popup-orphans.txt    # 먼저 눈으로 확인한다(파일이 없다는 오류가 나오면 위 단계가 실패한 것)
ready && docker run --rm -v cms_notice_attachments_prod:/data -v "$PWD":/work alpine:3.20 sh -c '
  test -f /work/popup-orphans.txt || { echo "popup-orphans.txt 없음 — 삭제 중단"; exit 1; }
  while IFS= read -r k; do case "$k" in ""|/*|*..*) ;; *) rm -f -- "/data/popup/$k";; esac; done < /work/popup-orphans.txt'

# ⑥ 앱 기동
docker start cms-app-prod
```

파일을 DB보다 나중에 지우므로 중간에 실패해도 남는 것은 "행 없는 파일"뿐이고, 다음 회수의 ⑤가 정리한다. ⑤는 공지 첨부·게시글 첨부의 잔존 파일(같은 원인)도 함께 회수하고, ⑤-2는 프로필 파일의 잔존분을 회수한다. 삭제된 공지는 API로 복원할 수 없으므로 그 이미지를 회수해도 사용자 기능 손실은 없다.

## 미디어 라이브러리 (2026-10-12, adversarial-review/plan/PLAN-media-library.md)

배너·팝업·편집기 본문 이미지는 `content_image` 한 테이블이 원본이다(`/admin/media/manage`, 위임 기능 `MEDIA`). 파일은 `banner/`·`popup/`·루트에 있고 `content_image.namespace`(빈 문자열 = 루트)에 위치가 기록된다. 배너·팝업·게시글과 이미지의 연결은 `content_image_ref` 한 곳이고 같은 이미지를 여러 소유자가 함께 쓸 수 있다(`banner`·`popup`의 옛 직접 저장 컬럼은 V3가 지웠다).
- **위임 규칙**: `MEDIA` 읽기가 있는 MANAGER는 라이브러리 계열(라이브러리 업로드·배너·팝업 출처) 이미지만 보고 고를 수 있다. **게시판 본문 이미지(`BOARD` 출처)는 ADMIN만 목록·삭제할 수 있다**(다른 게시판의 비공개 이미지가 권한 밖으로 새지 않게). 배너·팝업·게시글에서 라이브러리 이미지를 **고르는 것**은 저장 시점에 현재 `MEDIA` 읽기 권한을 다시 판정한다 — 회수하면 새로 고를 수 없지만 이미 쓰고 있는 이미지는 유지된다.
- **삭제 규칙**: 사용 중인 이미지는 삭제할 수 없다(409, 메시지에 사용처 요약). 배너·팝업을 지우면 그 배너·팝업이 직접 올린(`BANNER`/`POPUP` 출처) 이미지는 다른 소유자가 없을 때 이미지 행·파일·카운터까지 함께 정리되고, 라이브러리에 올린(`LIBRARY` 출처) 이미지는 남는다.
- **운영 확인 SQL**(읽기 전용):
  ```sql
  -- 카운터가 원본과 일치하는지
  SELECT u.total_bytes, u.total_count, COALESCE(SUM(i.file_size), 0) AS real_bytes, COUNT(i.id) AS real_count
  FROM content_image_usage u LEFT JOIN content_image i ON 1 = 1 WHERE u.id = 1;
  -- 어긋나면 앱이 정지된 상태에서 원본으로 재계산한다(쓰기 SQL)
  -- UPDATE content_image_usage SET total_bytes = (SELECT COALESCE(SUM(file_size), 0) FROM content_image),
  --        total_count = (SELECT COUNT(*) FROM content_image) WHERE id = 1;
  -- 이미지형인데 참조가 없는 배너·팝업(공개에서 제외되고 관리 화면에 이미지가 비어 보인다)
  SELECT 'BANNER', b.id FROM banner b WHERE NOT EXISTS (SELECT 1 FROM content_image_ref r WHERE r.owner_type = 'BANNER' AND r.owner_id = b.id)
  UNION ALL
  SELECT 'POPUP', p.id FROM popup p WHERE p.popup_type = 'IMAGE' AND NOT EXISTS (SELECT 1 FROM content_image_ref r WHERE r.owner_type = 'POPUP' AND r.owner_id = p.id);
  ```
- **수동 회수 개정(위 "수동 회수 절차")**: ②는 "살아 있는 어떤 소유자(게시글·배너·팝업)도 참조하지 않는" 이미지만(라이브러리 직접 업로드 제외), ⑤는 루트 영역(`namespace = ''`)의 `content_image` 키 + `post_attachment` 키를 보존하고, ⑤-3·⑤-4는 `content_image`의 `namespace = 'banner'`/`'popup'` 키를 보존한다. 일회용 MariaDB·볼륨에서 이 블록을 그대로 실행해 (a) 배너를 지운 뒤 게시글만 참조하는 이미지 파일 보존, (b) 소유자 없는 배너·팝업 출처 이미지 회수, (c) 라이브러리 업로드 미참조 이미지 보존, (d) 행 없는 파일만 삭제됨을 확인했다(`PLAN-media-library.md` 구현·검증 결과).

## 알려진 제약

- `GET /swagger-ui.html`·`/v3/api-docs`는 springdoc 비활성 시(prod) 핸들러가 등록되지 않는다. `/admin/api/**` 밖 경로라 `GlobalApiExceptionHandler.API_MATCHER`에 걸리지 않고 `CustomErrorController`의 일반 HTML 404(`error/404.html`)로 응답한다(2026-08-06 `7c64307` #26로 해결됨 — 이전에는 500이었다. 상세는 `docs/troubleshooting.md` "핸들러가 아예 없는 경로가 404가 아니라 500으로 응답됨" 참조).
- 이 문서의 절차는 로컬/서버에서 사람이 직접 실행하는 것을 전제로 한다(`prod-up.sh`는 호스트 `curl`이 필요). 단 CI의 `prod-smoke` job은 `scripts/ci/`의 래퍼로 `prod-up.sh`·`prod-backup.sh`·`prod-restore.sh`를 폐기 가능한 러너에서 그대로 호출해 검증한다(아래 "CI 배포 게이트" 참조).
- **백업은 오프사이트 보관을 포함하지 않는다** — 같은 호스트 디스크에만 있는 백업은 디스크 전체 손실을 막지 못한다(범위 밖, 로드맵 "후속 과제 — ①"에 기록됨).
- **파일 복구가 중단되면 이전 상태·빈 상태·일부만 새 데이터로 교체된 혼합 상태 중 하나로 남을 수 있다** — 볼륨 내부 스테이징 후 최상위 항목 단위로 교체하는 방식이라 완전한 원자성은 아니다. 이 경우 `scripts/prod-restore.sh`의 트랩이 앱을 정지 상태로 유지하고 복구 직전 안전 백업 경로를 안내한다.
- **`_CMS_BACKUP_INTERNAL_CALL` 환경변수를 수동으로 설정하면 잠금·보존 정리를 우회할 수 있다** — 단일 신뢰 운영자가 로컬에서 수동 실행하는 도구라는 위협 모델을 전제로 문서화된 제약으로만 남긴다(직접 설정하지 않는다).
- **정규(quiesced) 백업은 자동화돼 있지 않다** — 운영자가 매일 수동으로 실행해야 하며, 실행을 잊으면 RPO 24시간 목표가 실제로는 지켜지지 않는다(스크립트가 실행 누락 자체를 감지·알리지 않음). 실사용자 운영 규모가 커지면 무인 자동화(cron이 stop/start까지 수행)를 재검토한다.
- **격리 drill 1회가 향후 모든 개별 복구를 보증하지 않는다** — `docs/verification/recovery-drill.md`는 특정 이미지·fixture·백업 세트의 복구 가능성만 증명한다. 매 실제 운영 복구 후에는 위 "복구" 절의 체크리스트를 별도로 수행해야 한다.
