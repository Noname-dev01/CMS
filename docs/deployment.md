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

## 무인증 공개 엔드포인트 레이트리밋

`cms.rate-limit.*`(전 프로파일 공통값, `application.yml`)이 `/notices/**`·비밀번호 재설정 API를 토큰 버킷으로 방어한다. 상세 설계는 `adversarial-review/plan/PLAN-public-endpoint-rate-limit.md` 참조.

- **운영 튜닝**: `CMS_RATE_LIMIT_ENABLED`(기본 `true`)로 전체를 켜고 끌 수 있다. 개별 규칙의 한도는 `application.yml`을 수정해야 한다(환경변수 인덱스 오버라이드는 지원하지 않음 — Spring Boot relaxed binding은 리스트 프로퍼티의 환경변수 오버라이드를 신뢰하기 어렵다).
- **다중 인스턴스 배포 시 한도가 사실상 배가된다** — 각 인스턴스가 독립된 Caffeine 캐시를 가지므로, 로드밸런서 뒤에 인스턴스 N개를 두면 실질 한도는 설정값의 최대 N배가 된다(현재 단일 인스턴스 전제와 일치, `docker-compose.prod.yml` 변경 없이는 발생하지 않는 시나리오).
- **fail-open 잔여 위험**: 캐시가 포화되는 극단적 상황(대량 IP 회전 공격 등)에서는 개별 IP의 정확한 누적치 보장이 흐트러질 수 있다 — 정확한 유량 계약을 보장하는 게이트웨이가 아니라 "무제한 요청을 값싸게 차단하는 최소 방어"가 목표이기 때문이다. 완전한 정확성이 필요하면 Redis 등 외부 원자적 저장소가 필요하나 이번 범위를 벗어난다.
- nginx 리버스 프록시 도입 시 `server.forward-headers-strategy=native`를 설정하면 레이트리밋의 IP 추출(`request.getRemoteAddr()`) 코드는 변경 없이 실 클라이언트 IP를 기준으로 동작한다 — 단, nginx가 클라이언트 제공 `X-Forwarded-For`를 그대로 통과시키지 않고 자신이 관측한 실제 peer IP로 재작성해야 하고, 애플리케이션 포트(8080)에 외부에서 직접 접근할 수 없어야 한다(아래 "배포 대상(ingress)" 참조).

## 배포 대상(ingress) (감사 M-05, remediation-plan.md PR 6)

**실제 ingress(리버스 프록시·TLS 종료 위치·호스팅)가 아직 정해지지 않았다** — 이 문서·`docker-compose.prod.yml`은 `127.0.0.1:8080` 루프백 바인딩까지만 다루며, 그대로 인터넷에 노출하면 안 된다. ingress topology가 확정되지 않은 상태에서 특정 제품(nginx 등) 설정을 미리 만들지 않는다 — 대신 실제 외부 공개 전에 통과해야 할 체크리스트만 `docs/verification/deployment-edge.md`에 문서화해뒀다.

**현재 코드에 이미 존재하는 IP 소스 불일치(체크리스트에서 짚음)**: `RateLimitFilter`는 `request.getRemoteAddr()`(위조 불가)를 쓰지만, `AdminActionLogAspect`(감사 로그)는 `X-FORWARDED-FOR`/`X-Real-IP` 헤더를 검증 없이 우선 사용한다 — 리버스 프록시 뒤에서는 두 코드가 서로 다른 IP를 신뢰하게 될 수 있다. 이 불일치 자체는 이번 문서화 작업의 범위가 아니며(로드맵 Top5 ③ H-03·M-01 "감사 로그 신뢰성 강화" 항목 참조), 외부 공개 전 실제 ingress 경로에서 반드시 재확인해야 한다.

**`docs/verification/deployment-edge.md`의 체크리스트는 전부 "미검증(ingress 미확정)"으로 남아 있다.** 이 문서화 작업의 완료는 로드맵 "후속 과제 — ① 실배포 인프라"(nginx·TLS 인증서·실제 호스팅·CD 파이프라인까지 포함) 항목 자체의 완료를 의미하지 않는다 — 그 항목은 실제 ingress가 구축·검증돼야 완료된다.

## CI 배포 게이트 (감사 M-06, adversarial-review/plan/PLAN-ci-prod-gates.md)

`.github/workflows/ci.yml`의 `prod-smoke` job이 `test` job과 병렬로 다음을 자동 검증한다. 머지 차단은 저장소 브랜치 보호에 `prod-smoke`를 **필수 체크로 등록**해야 성립한다(`docs/branching.md`, 사용자 설정).

- **이미지 참조 검사**(`scripts/ci/check-image-refs.sh`): `mariadb`·`eclipse-temurin` 참조가 전부 digest로 고정돼 있고 mariadb 참조의 sha256이 모두 같은지 확인.
- **스모크**(`scripts/ci/prod-smoke.sh`): 실제 `prod-up.sh`로 이미지 빌드·기동 → health 200 → `/admin/login` 200 → Actuator 대표 3경로(`env`·`beans`·`metrics`)가 무인증 302→`/admin/login`, ADMIN 인증 403 → ADMIN 로그인 후 `/swagger-ui.html`·`/v3/api-docs` 404. 증명 범위는 이 열거 항목뿐이며 `/actuator/**` 전체가 아니다.
- **백업·복구 왕복**(`scripts/ci/prod-backup-restore-roundtrip.sh`): quiesced 백업 → DB 행·볼륨 파일 변경(복구 직전 변경 반영 단언) → 잘못된 DB 이름 입력이 "입력 불일치" 사유로 거절되고 상태 불변 → 실제 복구 → DB 행·sha256·소유권 `10001:10001` 복원, 백업 이후 추가분 소멸. `recovery-drill.md`의 수동 drill(앱 레벨 첨부 조회)을 대체하지 않는다.
- **이미지 스캔**(Trivy, 빌드된 `cms-prod-app`): 수정판 있는 HIGH/CRITICAL이면 실패. OS 레이어와 fat jar 내부 라이브러리를 모두 본다. 예외는 `.trivyignore.yaml`의 `statement`·`expired_at`·`paths`로만 두며 만료되면 다시 실패한다.

**로컬 재현**: 스크립트는 고정 이름의 `cms-*-prod` 컨테이너·`cms_*_prod` 볼륨·`.env.prod`를 만들고 지운다. 폐기 가능한 Docker 환경에서만 실행하며 `CMS_CI_DISPOSABLE_DOCKER=1 bash scripts/ci/prod-smoke.sh`로 시작한다. 기존 `.env.prod`·prod 컨테이너·볼륨이 있거나 127.0.0.1:8080이 사용 중이면 아무 변경 없이 중단한다(dev 스택이 8080을 쓰고 있으면 먼저 정지해야 한다).

### 이미지 digest 갱신

이미지는 `image:tag@sha256:...`(Dockerfile 2, compose 2, `prod-backup.sh`·`prod-restore.sh`)로 고정돼 있고 테스트 컨테이너(`MariaDbContainerSupport`)만 Testcontainers/Spring Boot 이름 검증 제약으로 `mariadb@sha256:...`(태그 없음)를 쓴다. Dependabot(`.github/dependabot.yml`)이 Dockerfile·compose의 digest 갱신 PR을 만들지만 **스크립트·Java 리터럴은 추적하지 못한다** — 그 PR에서 나머지 mariadb 참조를 같은 digest로 직접 맞춰야 하며, 어긋나면 `check-image-refs.sh`가 CI를 실패시킨다. 새 digest는 `docker buildx imagetools inspect <image:tag>`로 조회한다.

### 스캔 실패 시 절차

새 CVE가 공개돼 `prod-smoke`가 빨개지면 (1) 수정 버전으로 상향, (2) 불가하면 `.trivyignore.yaml`에 CVE ID·`paths`·사유·책임자·`expired_at`을 적어 예외 처리한다. 예외 없이 우회하려면 필수 체크 설정을 임시 해제하는 방법뿐이며, 그 사실을 PR에 기록한다. Spring Boot BOM이 관리하는 라이브러리는 `build.gradle`의 `ext['tomcat.version']`·`ext['jackson-bom.version']` 오버라이드로 올리며(Boot가 이 버전 이상을 관리하게 되면 제거), 현재 값과 사유는 해당 파일 주석에 있다.

## 알려진 제약

- `GET /swagger-ui.html`·`/v3/api-docs`는 springdoc 비활성 시(prod) 핸들러가 등록되지 않는다. `/admin/api/**` 밖 경로라 `GlobalApiExceptionHandler.API_MATCHER`에 걸리지 않고 `CustomErrorController`의 일반 HTML 404(`error/404.html`)로 응답한다(2026-08-06 `7c64307` #26로 해결됨 — 이전에는 500이었다. 상세는 `docs/troubleshooting.md` "핸들러가 아예 없는 경로가 404가 아니라 500으로 응답됨" 참조).
- 이 문서의 절차는 로컬/서버에서 사람이 직접 실행하는 것을 전제로 한다(`prod-up.sh`는 호스트 `curl`이 필요). 단 CI의 `prod-smoke` job은 `scripts/ci/`의 래퍼로 `prod-up.sh`·`prod-backup.sh`·`prod-restore.sh`를 폐기 가능한 러너에서 그대로 호출해 검증한다(아래 "CI 배포 게이트" 참조).
- **백업은 오프사이트 보관을 포함하지 않는다** — 같은 호스트 디스크에만 있는 백업은 디스크 전체 손실을 막지 못한다(범위 밖, 후속 과제로 로드맵에 기록 예정).
- **파일 복구가 중단되면 이전 상태·빈 상태·일부만 새 데이터로 교체된 혼합 상태 중 하나로 남을 수 있다** — 볼륨 내부 스테이징 후 최상위 항목 단위로 교체하는 방식이라 완전한 원자성은 아니다. 이 경우 `scripts/prod-restore.sh`의 트랩이 앱을 정지 상태로 유지하고 복구 직전 안전 백업 경로를 안내한다.
- **`_CMS_BACKUP_INTERNAL_CALL` 환경변수를 수동으로 설정하면 잠금·보존 정리를 우회할 수 있다** — 단일 신뢰 운영자가 로컬에서 수동 실행하는 도구라는 위협 모델을 전제로 문서화된 제약으로만 남긴다(직접 설정하지 않는다).
- **정규(quiesced) 백업은 자동화돼 있지 않다** — 운영자가 매일 수동으로 실행해야 하며, 실행을 잊으면 RPO 24시간 목표가 실제로는 지켜지지 않는다(스크립트가 실행 누락 자체를 감지·알리지 않음). 실사용자 운영 규모가 커지면 무인 자동화(cron이 stop/start까지 수행)를 재검토한다.
- **격리 drill 1회가 향후 모든 개별 복구를 보증하지 않는다** — `docs/verification/recovery-drill.md`는 특정 이미지·fixture·백업 세트의 복구 가능성만 증명한다. 매 실제 운영 복구 후에는 위 "복구" 절의 체크리스트를 별도로 수행해야 한다.
