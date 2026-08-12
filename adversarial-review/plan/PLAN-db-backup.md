# PLAN — DB·파일 백업 전략 수립

> 작성일: 2026-08-12
> 로드맵 근거: `adversarial-review/project-direction-roadmap.md:242` "후속 과제 — ① prod 프로파일 완료 시 발견" — "**DB 백업 전략**: named volume 보존만으로는 백업이 아니다 — 실배포 전 별도 수립 필요"
> 관련 기록: `adversarial-review/deploy-check-2026-07-30.md:86`도 동일 항목을 "실배포 전" 차단 조건으로 표기
> `/suggestRoadmap`(2026-08-12)에서 사용자가 3개 후보 중 선정, plan 모드 정찰·설계 후 승인 완료. 이 문서는 `/plan-review-loop` 리뷰 대상으로 제출한다.

## 개정 이력

- v1 (2026-08-12): 최초 작성. 백업 방식(논리 덤프+파일 tar)·복구 제공 방식(대화형 확인 스크립트)·자동화 범위(수동+보존 정리)·검증 환경(prod 실기)을 사용자와 확정.
- v2 (codex 1차 리뷰 반영 — no-ship, 12개 중 11개 수용·1개 사용자 결정): 안전 백업 자기삭제, "콜드 백업" 실행 불가(정지 상태 백업으로 대체), manifest↔DB명 대조, 파일 복구 비원자성, 복구 순서, 동시 실행 잠금(flock), 오프사이트 범위 축소(사용자 확정), 기밀성(umask), `--events`, 무결성 검사, 전처리 검증, "orphan=무해" 표현을 반영.
- v3 (codex 2차 리뷰 반영 — no-ship, 9개 전부 수용): manifest DB명만으로는 dev/prod 동일 DB명 구분 불가(environment_id 마커 도입), 복구 후 앱 미정지, **flock이 이 환경에 실측상 없어 mkdir 잠금으로 교체**, 안전 백업 경로 stdout 반환, 파일 스테이징 위치, 트랩의 원래 실행 상태 보존, `flyway_max_version` 파이프라인 조기 종료, 첨부 볼륨 부재 은폐, 타임스탬프 충돌을 반영.
- v4 (codex 3차 리뷰 반영 — no-ship, 11개 중 10개 수용·1개 사용자 결정): environment_id 부재=빈 볼륨 가정 오류, `docker stop` 실패 무시, mkdir 잠금 진단 정보 부재, 공간 검사 시점·정확도, `.restore-staging` 재귀 충돌, `USE` 문 fail-open, 복구 후 기동 정책(사용자 확정: 항상 재기동), `flyway_max_version` 문자열 정렬 버그, SHA256SUMS 3줄 검증, `--numeric-owner` UID 가정(Dockerfile 고정), 컨테이너/볼륨명 오버라이드(테스트 가능성)를 반영.
- v5 (codex 4차 리뷰 반영 — no-ship, 구체적 버그 7개 + **메타 지적(설계 복잡도)에 대한 사용자 결정** 반영):
  - **메타 지적(사용자 확정 2026-08-12): 설계를 단순화한다.** codex가 지적한 패턴 — "매 라운드 패치가 다음 라운드의 새 결함을 낳는다"(v4의 잠금 진단 정보 추가가 `rmdir`을 항상 실패시켜 정상 종료 때도 잠금이 영구히 안 풀리는 회귀를 만듦)가 실제로 반복되고 있고, "단일 신뢰 운영자의 로컬 수동 백업 도구"라는 목표에 비해 영속 마커 상태 머신 + 3종 환경변수 오버라이드 + 재진입 잠금 진단이 과도하다는 지적을 그대로 수용 — **`environment_id` 마커 상태 머신과 `CMS_DB_CONTAINER`/`CMS_APP_CONTAINER`/`CMS_FILES_VOLUME` 오버라이드를 전면 제거**한다. 컨테이너·볼륨명은 다시 하드코딩(v2 수준)으로 되돌리고, 오환경 복구 방지는 manifest DB명 + SQL `USE` 문 대조 두 겹만으로 충분하다고 판단한다(단일 prod 환경 전제 — 여러 환경이 같은 인프라 관례를 공유하게 되면 재검토 대상). "dev 백업을 prod에 복구 시도" 검증은 수기 조작 fixture(백업 사본의 `manifest.txt` DB명만 바꾸고 체크섬 재계산)로 대체한다 — 스크립트 자체에 테스트용 확장을 남기지 않는다.
  - **수용(차단1, `info` 파일 때문에 정상 종료 때도 잠금이 안 풀림)**: `rmdir`은 빈 디렉터리만 지우는데 `info` 파일이 남아 있어 모든 정상 실행이 잠금을 영구히 남긴다는 지적 — v4가 스스로 만든 회귀. 단순화 결정에 따라 **`info` 진단 파일 자체를 없애고 plain `mkdir`/`rmdir` 잠금으로 되돌린다**(v3 수준) — 이 회귀 클래스를 원천 제거. 스테일 락은 자동 회수 대신 `docs/deployment.md`에 수동 해제 절차만 안내한다.
  - **수용(차단2, 볼륨명 오타/부재가 자동으로 새 빈 볼륨을 만들어 "재해복구"로 오통과)**: restore에 `docker volume inspect` 사전 확인이 없어 `docker run -v` 최초 호출이 없는 볼륨을 자동 생성해버릴 수 있다는 지적 — 타당. restore 시작 시 backup과 동일하게 `docker volume inspect`로 존재를 확인해 없으면 즉시 중단하도록 추가. (환경변수 오버라이드 제거로 이 위험의 상당 부분은 이미 없어졌지만, 방어를 명시적으로 남긴다.)
  - **수용(높음3, environment_id 마커 유실 시 안내가 실제로는 복구를 못 시킴)**: "먼저 백업해서 마커 발급" 안내는 backup이 항상 새 ID를 자동 발급해버려 과거 백업들의 ID와 영구히 어긋난다는 지적 — 위 메타 지적 수용으로 environment_id 자체를 폐기했으므로 이 문제 자체가 해소(더 이상 마커가 없음).
  - **수용(높음4, `gzip -l`이 4GiB 이상에서 32비트 랩어라운드로 부정확)**: gzip 포맷이 원본 크기를 `2^32` 모듈로 기록해 대형 아카이브에서 실제보다 훨씬 작게 보고될 수 있다는 지적(GNU 공식 문서 인용) — 타당. `gzip -l` 대신 `gzip -dc | wc -c`로 실제 압축 해제 바이트 수를 직접 세도록 교체(크기 무관하게 정확).
  - **수용(높음5, `.restore-staging` 검사가 `pipefail`+`grep -q`의 SIGPIPE 은폐로 재차 fail-open될 수 있음)**: `tar tzf | grep -qE ...`에서 `grep -q`가 매치를 찾고 먼저 종료하면 `tar`가 SIGPIPE로 죽어 파이프라인 전체 종료 코드가 실패로 바뀌고(`pipefail`), `if` 조건이 거짓이 되어 정작 발견해야 할 예약 이름을 놓칠 수 있다는 지적 — v4가 SQL 검증에서 없앤 것과 같은 클래스의 버그가 새 코드에 재등장했다는 정확한 지적. tar 목록을 명령어 치환(`$(...)`)으로 한 번에 전부 캡처(파이프 조기 종료 없음)한 뒤, 그 문자열을 here-string(`<<<`)으로 `grep -q`에 넘긴다 — 파이프라인이 아니라 단일 명령이라 `pipefail` 상호작용 자체가 없다. 같은 클래스의 잠재 결함이 있던 `docker inspect ... | grep -q true` 패턴들도 전부 `[ "$(...)" = "true" ]` 직접 비교로 교체(파이프 자체를 제거).
  - **수용(중간6, `SHA256SUMS` "정확히 3파일" 검증이 파일명 suffix만 비교해 `shadow/db.sql.gz` 같은 값도 통과)**: `grep -qE "  ${f}\$"`가 접미사만 비교한다는 지적, `.`이 이스케이프 안 됐다는 지적 — 타당. 3줄을 순서대로(백업 스크립트가 항상 `db.sql.gz files.tar.gz manifest.txt` 순으로 생성) 정확한 형식(`^[0-9a-f]{64}  <파일명>$`)으로 검증한 뒤에만 `sha256sum -c`를 실행하도록 순서도 바꾼다.
  - **수용(중간7, Dockerfile이 UID만 고정하고 GID는 고정하지 않음)**: "UID/GID 고정"이라고 서술했으나 실제로는 `useradd -m -u 10001 appuser`뿐이었다는 지적 — 정확하다. `groupadd -g 10001 appuser` + `useradd -g 10001`을 명시적으로 추가.
  - **반박**: 없음.
- v6 (codex 5차 리뷰 반영 — no-ship, 6개 전부 수용, 사용자가 "codex 라운드 한 번 더"로 결정 — 구조 변경 없는 국소 수정만):
  - **수용(차단1, `.restore-staging` 정규식이 `./` 접두사 없는 tar 엔트리는 놓침)**: `^\./\.restore-staging(/|$)`가 `.restore-staging/...`(선두 `./` 없는 엔트리)는 걸러내지 못한다는 지적, 이 경로로 스테이징 재귀 충돌·부분 복구가 재현된다는 지적 — 정확하다. 정규식을 `^(\./)?\.restore-staging(/|$)`로 수정해 두 형태를 모두 거부.
  - **수용(높음2, `SHA256SUMS` 검증 순서가 계획 서술과 반대로 구현됨)**: 결정 14 텍스트는 "형식·순서 검증 후 `sha256sum -c`"라고 서술했는데, v5의 실제 `prod-restore.sh` 코드는 `sha256sum -c`를 먼저 실행하고 있었다는 지적 — 제 실수였다. 형식·줄 수 검증을 `sha256sum -c`보다 먼저 실행하도록 스크립트 순서를 텍스트와 일치시킨다.
  - **수용(중간3, `gzip -dc | wc -c`가 "필요 디스크 공간"의 정확한 값은 아님)**: 측정값은 tar 스트림의 논리적 바이트 수일 뿐, 파일시스템 블록·inode 오버헤드(작은 파일이 많으면 실제 소비량이 더 큼)는 반영하지 않는다는 지적 — 정확하다. "정확히 계산"이라는 표현을 "보수적 추정치"로 정정하고, 완료 기준·테스트 계획의 관련 문구도 같이 낮춘다.
  - **반박(경미, 중간4, tar 목록 전체를 변수에 캡처하는 방식이 메모리를 씀)**: 엔트리가 매우 많으면 메모리 사용이 커진다는 지적 자체는 사실이나, 이 프로젝트 규모(첨부 10MB×5/공지, 로컬 단일 운영자 도구)에서 tar 목록 텍스트는 무시할 수준이라 실질적 위험이 아니라고 판단 — 사용자가 선택한 "단순함 우선" 방향과도 맞다. 규모 가정만 정찰 섹션에 명시.
  - **수용(중간5, manifest DB명+`USE` 문이 "오환경 방지"라기보다 "동일 DB명의 중복 표현"에 가까움)**: 다른 Docker 데몬/호스트에 같은 이름으로 존재하는 prod 컨테이너, 과거 재구축된 다른 prod 인스턴스의 동일 DB명 백업, manifest·SQL을 함께 재작성하고 체크섬을 다시 계산한 백업까지는 이 두 검증으로 막지 못한다는 지적 — 정확하다. "오환경 복구 방지"라는 표현 대신, 이 도구가 전제하는 구체적 조건(단일 Docker 데몬·단일 prod 인스턴스·백업 디렉터리 출처 신뢰)을 명시적으로 서술한다 — 기능 추가(마커 재도입) 대신 계약을 정확히 좁히는 방향을 택함(v5 단순화 취지와 일치, codex도 이 방향을 권고).
  - **수용(중간6, SQL `USE` 방어 자체를 실패시키는 테스트가 계획에 없음)**: 기존 테스트 7은 manifest만 변조해 첫 번째 방어(manifest DB명 대조)만 검증하고, 두 번째 방어(SQL `USE` 문 대조)를 실패시키는 fixture가 없었다는 지적 — 정확하다. SQL 페이로드의 `USE` 문만 변경한 별도 fixture 테스트를 테스트 계획에 추가.
  - **기타 경미 반영**: 잠금 해제 실패(`rmdir ... || true`)가 조용히 성공처럼 보인다는 리뷰 요약 코멘트를 반영해 실패 시 stderr 경고를 남기도록 소폭 수정(별도 번호 없이 코드에만 반영).
- **6차 확인 리뷰(v6) 결과: ship.** codex가 `.restore-staging` 정규식(`^(\./)?\.restore-staging(/|$)`)을 실제 Bash/GNU grep으로 5개 케이스(`.restore-staging`·`.restore-staging/x`·`./.restore-staging`·`./.restore-staging/x`·`x/.restore-staging`) 직접 실행해 의도대로 동작함을 확인, `SHA256SUMS` 검증 순서(3줄 확인 → 형식 확인 → `sha256sum -c`)가 코드에 실제로 그 순서로 반영됐음을 재확인, `${f//./\\.}` 이스케이프도 `db\.sql\.gz` 형태로 올바르게 치환됨을 확인. 비차단 권고 1건(테스트 16에 `.restore-staging/`·`./.restore-staging/` 두 형태를 각각 명시)만 반영하고 `plan-review-loop` 6라운드(5회 자동 + 사용자 승인 1회 연장) 종료. 구현 전 계획 리뷰라 신규 스크립트 실행 결과는 아직 없었다.
- v7 (구현 단계, 2026-08-12): 계획 승인 후 `feat/db-backup-strategy` 브랜치에서 구현 착수. `Dockerfile`(UID/GID 고정)·`scripts/prod-backup.sh`·`scripts/prod-restore.sh`(신규)·`Makefile`·`.gitignore`·`docs/deployment.md`를 계획대로 작성. **구현 중 신뢰할 수 없는 재현 방법(도구 호출 파라미터에 직접 타이핑한 텍스트로 이스케이프 테스트)에 기반해 `${f//./\\.}`가 "전혀 이스케이프되지 않는다"고 오판** — `${f//./\\\\.}`(백슬래시 4개)로 잘못 수정했다가, v8에서 프로그래밍적 재검증으로 정정(아래 참조).
- v8 (구현 단계, v7 정정, 2026-08-12): `printf '\\%.0s' $(seq 1 N)`으로 백슬래시 개수(1~4개)를 프로그래밍적으로 통제해 생성하고 각각 `bash -x`로 최종 확장된 정규식을 직접 관찰하는 신뢰할 수 있는 방법으로 재검증. 결과: 1개는 이스케이프 안 됨(`db.sql.gz`), **2개가 정확히 `db\.sql\.gz`를 만드는 정답**(원래 v6까지의 값이 옳았음), 3개는 2개와 동일, 4개는 `db\\.sql\\.gz`(틀림). `${f//./\\.}`(백슬래시 2개)로 되돌리고 실제 `scripts/prod-restore.sh` 파일에 `bash -x`를 직접 실행해 정상 백업 라인이 매칭되는 것과 SHA256SUMS 형식 검증(중간6) 및 오환경 방지(첫 번째 방어, 결정 9)가 실제로 동작하는 것을 재확인. 같은 김에 `sha256sum` 출력 형식이 이 환경(Windows Git Bash/MSYS, 바이너리 모드 기본값 → `<해시> *파일명`)과 실제 배포 대상 Linux(텍스트 모드 기본값 → `<해시>  파일명`)에서 서로 다름을 실측으로 발견 — 정규식을 `^[0-9a-f]{64} [ *]<파일명>$`로 두 형식 모두 허용하도록 수정(결정 14 갱신). `Dockerfile`의 `-C /source`·`df -Pk /target` 등 컨테이너 내부 경로를 `docker run`에 직접 인자로 넘기는 지점이 Windows Git Bash(MSYS)의 자동 경로 변환(`/source` → `C:/Program Files/Git/source`)에 걸려 `tar`/`df`가 실패하는 것도 실측으로 발견 — 해당 `docker run` 호출에 `MSYS_NO_PATHCONV=1`을 추가(결정 3 범위 확장, `sh -c '...'`로 감싼 블록은 영향 없음을 확인). 결정 14·"변경 파일" 섹션의 코드 스니펫 전부 최종본으로 수정.

## Context

`scripts/prod-down.sh`는 `-v`/`--volumes`를 쓰지 않아 named volume이 보존되지만, 이는 "실수로 지우지 않음"일 뿐 운영자의 잘못된 대량 삭제나 볼륨 데이터 오염 같은 논리적 장애에는 아무 대비가 없다. 실행 로드맵 Top 3(2026-07-29 선정)가 전부 완료된 지금, 3단계(운영 경험)의 남은 조각 중 스키마·인가 정책 변경 없이 지금 바로 착수 가능하면서 방치 비용이 가장 큰 항목이다.

**범위**: **논리적 오삭제·볼륨 오염으로부터의 로컬 롤백**을 목표로 한다. 물리 디스크 손상·호스트 전체 유실은 다루지 않는다(오프사이트는 후속 과제). **v5 추가**: 이 도구는 **단일 prod 환경**을 전제로 한다 — 여러 prod 환경이 같은 볼륨/컨테이너 명명 관례를 공유하는 상황(예: 다중 인스턴스 배포)은 범위 밖이며, 그런 상황이 되면 재검토한다.

**의도한 결과**: `make prod-backup` 한 번으로 DB와 첨부/프로필 파일이 함께 시점 백업되고, 그 백업본으로 실제 복구가 되는 것까지 실기 검증된 상태.

### 확정된 설계 결정 (사용자 승인)

| 쟁점 | 결정 |
|------|------|
| 백업 방식 | 논리 덤프 + 파일 tar |
| 복구 제공 | 대화형 확인 스크립트 — Makefile 타깃 없음, 우회 플래그 없음 |
| 자동화 범위 | 수동 실행 + 보존 정리 |
| 검증 환경 | prod 스택 실기 + Playwright |
| 장애 모델 범위 | 논리적 오삭제·볼륨 오염만. 디스크 손상·호스트 유실은 범위 밖 |
| 동시성 제어 | `mkdir` 기반 원자적 잠금(flock 없음, 실측 확인) — **v5: 진단 정보 파일 없이 단순 형태로 되돌림** |
| 복구 후 앱 기동 정책 | 항상 재기동 — 복구 전 상태와 무관 |
| **설계 복잡도 (v5)** | **단순화 — environment_id 마커·컨테이너/볼륨명 오버라이드 폐기, 단일 prod 환경 전제** |

## 스키마 · 인가 정책 영향

- **스키마 변경: 없음.** Flyway 최대 버전 V11 유지.
- **인가 정책 변경: 없음.**
- **신규 의존성: 없음.**
- **`Dockerfile` 2줄 수정**: `appuser` UID/GID 고정 — 애플리케이션 동작 변경 없음.

## 정찰로 확인한 사실 (설계 근거)

- **prod db는 호스트 포트를 열지 않는다** → 백업/복구는 `docker exec cms-db-prod ...` 경유가 유일한 경로.
- **백업 대상은 2종의 named volume**: `cms_db_data_prod`, `cms_notice_attachments_prod`(첨부파일 + 프로필 이미지 공유).
- **비밀번호를 명령줄에 노출하지 않는 관례** — `docker-compose.prod.yml:19-21`.
- **`MARIADB_*` 변수는 없다** — 전부 `MYSQL_ROOT_PASSWORD`/`MYSQL_DATABASE`/`MYSQL_USER`/`MYSQL_PASSWORD`.
- **파괴적 작업을 원클릭 스크립트화하지 않는다는 명시적 원칙**(`scripts/prod-down.sh:12-15`).
- **파일 소유자는 `appuser`인데 UID가 고정돼 있지 않았다** (`Dockerfile:23` — 직접 확인, v4~v5에서 UID+GID 고정으로 해소).
- **`.env.prod`는 현재 존재하지 않는다** — 검증 단계에서 직접 생성. 잃어버려도 백업 데이터에는 영향 없음.
- **`.gitignore`에 백업 산출물을 거를 규칙이 없다.**
- **모든 테이블이 `ENGINE=InnoDB`로 명시돼 있다**(`V1__init_schema.sql`) → `--single-transaction` 전제 성립. `flyway_schema_history`도 Flyway가 MariaDB 기본 엔진(InnoDB)으로 자동 생성.
- **이 프로젝트의 Git Bash(MSYS)에는 `flock`이 없다**(직접 재검증). `mkdir`은 이 환경에서도 원자적.
- **`gzip -l`은 4GiB 이상 아카이브에서 32비트 랩어라운드로 부정확할 수 있다**(v5 — GNU 공식 문서로 확인, `gzip -dc | wc -c`로 대체).
- **`pipefail` 활성 상태에서 `producer | grep -q pattern`이 `if` 조건으로 쓰이면, `grep`이 매치를 찾고 먼저 종료해 producer가 SIGPIPE로 죽을 경우 파이프라인 전체 종료 코드가 실패로 바뀌어 `if` 조건이 거짓이 될 수 있다**(v5 — codex 4차 리뷰로 발견, 명령어 치환+here-string 또는 직접 문자열 비교로 회피).
- **기존 `scripts/*.sh` 관례**: `#!/usr/bin/env bash` + `set -euo pipefail`, 이모지 접두 `echo` 로깅, 벽시계 데드라인 폴링, RestartCount 안정성 재확인, 실패 시 `logs || true` → `stop || true` → `exit 1`.
- **파일 스토리지 루트**: `LocalDiskFileStorage`가 `yyyy/MM/dd/`(첨부)·`profile/`(프로필) 네임스페이스 분리.
- **규모 가정(v6)**: 첨부파일은 파일당 10MB·공지당 5개 상한(`NoticeAttachmentService`)이라 첨부 총량이 크게 늘지 않는다 — tar 엔트리 전체 목록을 셸 변수에 캡처해도(결정 10) 메모리 부담이 무시할 수준이라는 전제의 근거.
- **(v8) Windows Git Bash(MSYS)는 `/`로 시작하는 인자를 자동으로 host 경로로 바꾼다**: `docker run ... tar czf - -C /source .`처럼 컨테이너 내부 경로를 `docker run`의 직접 인자로 넘기면 `/source`가 `C:/Program Files/Git/source`로 잘못 치환돼 `tar`/`df` 등이 "파일 없음"으로 실패한다(직접 실행으로 실측). `sh -c '...'`로 감싼 스크립트 문자열 안의 경로는 영향받지 않는다(호스트가 아니라 컨테이너 안의 `sh`가 해석하므로). 영향받는 두 호출(`prod-backup.sh`의 tar 압축, `prod-restore.sh`의 `df` 공간 확인)에 `MSYS_NO_PATHCONV=1`을 접두로 붙여 해결(결정 3 범위 확장).
- **(v8) `sha256sum` 출력 형식이 플랫폼마다 다르다**: 이 환경(Windows Git Bash/MSYS)은 기본이 바이너리 모드(`<해시> *파일명`, 스페이스+별표)이고, 실제 배포 대상 Linux는 기본이 텍스트 모드(`<해시>  파일명`, 스페이스 2개)다(직접 실행으로 실측). `SHA256SUMS` 형식 검증 정규식(결정 14)이 두 형식을 모두 허용해야 한다.

## 핵심 설계 결정

### 1. `.env.prod`·`docker compose` 경유를 쓰지 않는다

백업/복구 스크립트는 `docker exec`/`docker run -v`/`docker stop|start`만 쓴다.

### 2. 비밀번호는 컨테이너 내부에서만, 작은따옴표 + `MYSQL_PWD`로 전달한다

```bash
docker exec cms-db-prod sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mariadb-dump -u root ...'
```

### 3. 파일 볼륨은 신규 이미지 없이 `mariadb:10.11`을 tar 헬퍼로 재사용한다

### 4. 시점 정합성은 "DB 먼저 → 파일 나중" 순서로 약하게 보장한다 (완전 정합은 결정 8)

orphan 파일은 "무해"가 아니라 **약한 보장**(PII 잔존 가능성)으로 서술한다.

### 5. 복구는 Makefile 타깃을 만들지 않고, 대화형 확인만으로 진행을 게이팅한다

### 6. 복구 직전 안전 백업을 남긴다

### 7. 잠금은 `mkdir` 기반이며, 진단 정보 없이 단순하게 유지한다 (v5 — 차단1 수용, 단순화)

**직접 재검증**: 이 프로젝트의 Git Bash(MSYS)에 `flock`이 없음을 확인했다.

```bash
LOCK_DIR="${CMS_BACKUP_LOCK_DIR:-${TMPDIR:-/tmp}/cms-prod-backup.lock.d}"
if [ "${_CMS_BACKUP_INTERNAL_CALL:-0}" != "1" ]; then
  if ! mkdir "$LOCK_DIR" 2>/dev/null; then
    echo "❌ 다른 백업/복구 작업이 이미 실행 중인 것으로 보입니다 ($LOCK_DIR)." >&2
    echo "   실행 중인 작업이 실제로 없다면(비정상 종료로 남은 잠금) 확인 후 수동으로 지우세요: rmdir \"$LOCK_DIR\"" >&2
    exit 1
  fi
  lock_acquired=1
fi
```

**v4에서 v5로 바뀐 점**: v4는 잠금 디렉터리 안에 `info`(pid·host·시작 시각) 파일을 남겼는데, `rmdir`은 빈 디렉터리만 삭제하므로 **모든 정상 종료에서도 잠금이 영구히 풀리지 않는 회귀**를 만들었다(codex 4차 리뷰 차단1 — v4가 스스로 만든 결함). 이번 라운드의 메타 지적("패치가 패치를 낳는다")을 사용자가 그대로 수용해 **진단 파일 기능 자체를 없애고** v3 수준의 단순한 `mkdir`/`rmdir`로 되돌린다. 스테일 락(비정상 종료로 남은 잠금)은 자동 회수하지 않으며 — 이 프로젝트의 "되돌릴 수 없는 판단을 원클릭화하지 않는다" 원칙과 일치 — `docs/deployment.md`에 "실행 중인 백업/복구 프로세스가 실제로 없는지 확인 후(`docker ps`, `ps` 등) 수동으로 `rmdir`" 절차만 안내한다.

restore가 내부에서 backup을 호출할 때는 `_CMS_BACKUP_INTERNAL_CALL=1`로 재진입 데드락을 피한다(변경 없음, 밑줄 접두사 = 내부 전용).

### 8. 완전 정합이 필요하면 "정지 상태 백업(quiesced backup)"을 쓴다

```bash
docker stop cms-app-prod
make prod-backup
docker start cms-app-prod
```

### 9. 복구 전 이중 검증: manifest DB명 + SQL 페이로드의 `USE` 문 (v5 — 단순화, environment_id 폐기)

**v4까지의 세 겹 방어(manifest DB명 + SQL `USE` 문 + `environment_id` 볼륨 마커)에서 `environment_id`를 제거하고 두 겹으로 단순화한다.** 근거: `environment_id`는 마커 생성 원자성·"마커 없음=빈 볼륨" 가정의 오류·마커 유실 시 복구 불가 등 라운드를 거듭할수록 새 결함을 계속 만들어냈고(codex 4차 리뷰 높음3), 이 도구가 전제하는 "단일 prod 환경"에서는 애초에 방어할 실질적 시나리오가 없었다(마커가 막으려던 "dev/prod가 우연히 같은 DB명을 씀" 문제는, 컨테이너/볼륨명 오버라이드가 함께 폐기되면서 스크립트 자체로는 재현 불가능해졌다).

**남은 두 겹**:

1. **manifest 기록 DB명 자동 대조**: 타이핑 확인 이전에 `manifest.txt`의 `database=`와 현재 컨테이너의 `MYSQL_DATABASE`를 비교, 불일치 시 즉시 중단.
2. **SQL 페이로드 자체와 대조**: `db.sql.gz`의 `USE \`...\`;` 문에서 실제 덤프 대상 DB명을 추출해 대조. 정확히 1개를 찾지 못하면(0개든 2개 이상이든) fail-open으로 통과시키지 않고 하드 중단한다.

MariaDB 버전·Flyway 최대 버전은 manifest에 정보로만 기록한다 — Flyway `ddl-auto: validate`+체크섬이 스키마 버전 불일치를 이중 방어한다.

**테스트 방법**: 오환경 백업 복구 차단은 수기 조작 fixture 2종으로 검증한다 — (1) `manifest.txt`의 `database=` 값만 바꾼 fixture(첫 번째 방어 검증), (2) `db.sql.gz` 안의 `USE` 문만 바꾼 fixture(두 번째 방어 검증, v6 추가 — codex 5차 리뷰 중간6). 스크립트 자체에는 테스트 전용 확장을 남기지 않는다.

**v6 — 이 방어가 실제로 보장하는 범위를 정확히 서술한다 (중간5 수용)**: "오환경 복구 방지"라는 표현은 이 두 검증이 실제로 막는 범위보다 넓게 들릴 수 있다는 지적(codex 5차 리뷰) — 정확하다. 이 두 검증이 실제로 전제하는 조건은 다음과 같다:

- 운영자가 접근하는 Docker 데몬은 하나뿐이다(다른 호스트/컨텍스트에 같은 이름의 `cms-db-prod`가 별도로 존재하는 상황은 다루지 않는다).
- prod 인스턴스는 단일하다(과거에 재구축된 "다른 prod"가 동일 DB명으로 백업을 남긴 상황은 다루지 않는다).
- 백업 디렉터리의 출처는 신뢰한다(manifest·SQL·체크섬을 함께 재작성한 위조 백업까지는 막지 못한다 — 단일 신뢰 운영자 위협 모델의 연장).

이 조건 안에서는 "dev 스택에서 만든 백업을 prod에 잘못 복구하는 실수", "오래된/다른 DB명의 백업을 잘못 지정하는 실수" 같은 realistic한 운영자 실수를 막는다. `environment_id` 같은 별도 마커를 재도입하지 않는다 — 기능을 늘리는 대신 계약을 정확히 좁히는 쪽을 택한다(v5 단순화 취지와 일치).

### 10. 파일 복구는 대상 볼륨 내부에서 "스테이징 → 검증 → 최상위 항목 단위 교체" 방식으로 한다

```bash
docker run --rm -i -v cms_notice_attachments_prod:/target mariadb:10.11 sh -c '
  set -e
  rm -rf /target/.restore-staging
  mkdir -p /target/.restore-staging
  tar xzf - --numeric-owner -C /target/.restore-staging
  find /target -mindepth 1 -maxdepth 1 ! -name ".restore-staging" -exec rm -rf {} +
  find /target/.restore-staging -mindepth 1 -maxdepth 1 -exec mv {} /target/ \;
  rmdir /target/.restore-staging
' < files.tar.gz
```

백업 시 `tar --exclude='.restore-staging'`로 원천 제외하고, 복구 전 검증 단계에서 tar 안에 이 이름이 남아 있으면(구버전 백업 등) 거부한다.

**v5 — `.restore-staging` 검사의 SIGPIPE 은폐 버그 수정 (높음5 수용)**: v4는 `tar tzf files.tar.gz | grep -qE '^\./\.restore-staging(/|$)'`를 `if` 조건으로 썼는데, `set -o pipefail` 상태에서 `grep -q`가 매치를 찾고 먼저 종료하면 아직 출력 중이던 `tar`가 SIGPIPE로 죽어 파이프라인 전체 종료 코드가 실패가 되고, `if` 조건이 거짓이 돼 정작 발견해야 할 예약 이름을 놓칠 수 있었다(codex 4차 리뷰) — v4가 SQL `USE` 검증에서 없앤 것과 같은 클래스의 버그가 새 코드에 재등장한 것. 명령어 치환으로 tar 목록을 한 번에 전부 캡처(조기 종료하는 소비자가 없어 SIGPIPE 자체가 안 생김)한 뒤, here-string으로 `grep -q`에 넘긴다 — 파이프라인이 아니라 단일 명령이라 `pipefail`과 상호작용하지 않는다:

```bash
tar_listing=$(tar tzf "$dir/files.tar.gz") || { echo "❌ 손상된 아카이브 구조"; exit 1; }
if grep -qE '^(\./)?\.restore-staging(/|$)' <<<"$tar_listing"; then
  echo "❌ 이 백업에는 예약된 이름(.restore-staging)의 항목이 포함되어 있어 안전하게 복구할 수 없습니다."
  exit 1
fi
```

이 방식은 tar 목록을 한 번만 읽어 구조 검증(`tar tzf`의 성공 여부)과 예약 이름 검사를 함께 수행한다.

**v6 — 정규식이 `./` 접두사 없는 엔트리를 놓치던 버그 수정 (차단1 수용)**: `^\./\.restore-staging(/|$)`는 `.restore-staging/...`(선두 `./` 없이 기록된 엔트리 — tar 구현·옵션에 따라 흔히 나타나는 형태)를 걸러내지 못해, 이 경로로 스테이징 재귀 충돌·부분 복구가 여전히 재현될 수 있었다는 지적(codex 5차 리뷰 차단1) — 정확하다. `^(\./)?\.restore-staging(/|$)`로 두 형태를 모두 거부하도록 수정.

**남는 한계**: "기존 항목 삭제 → 새 항목 이동" 구간이 중단되면 일부 항목만 새 데이터로 교체된 혼합 상태가 남을 수 있다. 결정 11의 트랩이 이 상태를 감지해 앱을 정지 상태로 유지하고 안전 백업 경로를 안내한다.

### 11. 복구 순서·트랩·기동 정책

**순서**: 확인 → 볼륨 존재 확인(v5 — 결정 신설, 아래 참조) → 앱 정지(실패 시 즉시 중단) → 안전 백업 → 대상 볼륨 여유 공간 확인(파괴 시작 전) → DB 복구 → 파일 복구 → 재기동 → health/RestartCount 검증.

트랩은 `destructive_started` 이후 실패에서 `docker stop`을 명시적으로 호출하고(재시작 루프 차단), 파괴적 변경 전 실패는 원래 실행 중이었을 때만 앱을 되살린다. **기동 정책(사용자 확정)**: 복구가 끝까지 성공하면 앱은 **항상** 재기동된다 — 복구 전 정지 상태였어도 마찬가지다. 대화형 확인 프롬프트에 이 정책을 명시한다.

**v5 신설 — 대상 볼륨 존재를 복구 시작 시 확인한다 (차단2 수용)**: v4는 컨테이너/볼륨명 오버라이드가 있는 상태에서 `docker volume inspect` 사전 확인 없이 `docker run -v`를 실행해, 존재하지 않는 볼륨명(오타 등)을 Docker가 자동으로 빈 볼륨으로 만들어버리고 이를 "재해복구"로 오통과시킬 수 있었다(codex 4차 리뷰 차단2). 오버라이드 폐기(v5 메타 결정)로 이 위험의 상당 부분은 이미 사라졌지만, 방어를 명시적으로 남긴다:

```bash
docker volume inspect "$FILES_VOLUME" >/dev/null 2>&1 \
  || { echo "❌ 볼륨 $FILES_VOLUME 이 없습니다 — prod 스택이 기동 중인지 확인하세요(make prod-up)."; exit 1; }
```

실제 재해복구 절차는 "새 prod 스택을 `make prod-up`으로 올린다(compose가 빈 볼륨을 자동 생성) → 그 위에 복구를 실행한다"이므로, 이 검사는 스택이 아예 기동되지 않은 상태에서의 실수만 막으며 정상 재해복구 플로우를 막지 않는다.

**v5 — `docker stop` 실패 처리는 v4에서 이미 수정됨(유지)**: `\|\| true` 없이 `set -e`가 자연히 전파하게 하고, 정지 후 `State.Running=false`를 재확인한다.

### 12. 백업 파일 기밀성 — `umask 077`

### 13. `mariadb-dump`에 `--events` 추가

### 14. 무결성 검사 — `tar tzf` + `manifest.txt` 체크섬 포함 + 정확한 3줄 형식·순서 검증 (v5 강화)

`SHA256SUMS`에 `manifest.txt`도 포함한다(v3). **v5 — 중간6 수용**: v4는 `grep -qE "  ${f}\$"`로 파일명을 접미사만 비교해 `shadow/db.sql.gz` 같은 값도 통과시킬 수 있었고 정규식의 `.`도 이스케이프되지 않았다는 지적(codex 4차 리뷰) — 정확하다. `SHA256SUMS`가 백업 스크립트의 생성 순서(`db.sql.gz files.tar.gz manifest.txt`)와 정확히 일치하는 3줄인지, 각 줄이 `^[0-9a-f]{64}  <파일명>$` 형식과 정확히 일치하는지 **`sha256sum -c`보다 먼저** 검증한다:

```bash
expected="db.sql.gz files.tar.gz manifest.txt"
i=0
for f in $expected; do
  i=$((i+1))
  line=$(sed -n "${i}p" "$dir/SHA256SUMS")
  echo "$line" | grep -qE "^[0-9a-f]{64} [ *]${f//./\\.}\$" \
    || { echo "❌ SHA256SUMS ${i}번째 줄이 예상과 다릅니다(기대: <64자리 해시>  ${f} 또는 <64자리 해시> *${f})."; exit 1; }
done
[ "$(wc -l < "$dir/SHA256SUMS")" -eq 3 ] || { echo "❌ SHA256SUMS 줄 수가 3이 아닙니다."; exit 1; }
```

**v8(구현 단계, v7 정정) — 백슬래시 개수는 2개(`\\.`)가 맞았다**: v7은 스크립트 파일을 직접 실행해 "2개로는 이스케이프가 전혀 안 된다"고 결론짓고 4개로 고쳤으나, 이는 신뢰할 수 없는 재현 방법(도구 호출 파라미터에 직접 타이핑한 텍스트 — 파라미터 인코딩 계층에서 백슬래시 개수가 달라질 수 있음)에 기반한 오판이었다. **`printf '\\%.0s' $(seq 1 N)`으로 백슬래시 개수를 프로그래밍적으로 통제해 1~4개를 전부 생성하고 각각 `bash -x`로 최종 확장된 정규식을 직접 관찰**하는 방식으로 재검증한 결과: 1개는 이스케이프가 전혀 안 됨(`db.sql.gz`, dot가 와일드카드로 남음), **2개가 정확히 `db\.sql\.gz`(dot 이스케이프됨)를 만드는 정답**, 3개도 2개와 동일한 결과(정확한 이유는 불명이나 실사용에는 무방), 4개는 `db\\.sql\\.gz`(백슬래시 리터럴+와일드카드 dot — 틀림)였다. `${f//./\\.}`(백슬래시 2개)로 되돌리고 실제 `scripts/prod-restore.sh` 파일에 `bash -x`를 직접 실행해 `db\.sql\.gz` 정규식이 나오는 것과 정상 백업 라인이 매칭되는 것을 재확인했다. v7의 "PowerShell 레이어가 우연히 버그를 가렸다"는 설명도 근거가 불충분해 철회한다 — 실제 원인은 특정 도구 호출 경로의 신뢰할 수 없는 텍스트 전달이었을 가능성이 높으나 완전히 규명하지는 못했다. 이후로는 이스케이프 검증이 필요하면 반드시 이번처럼 프로그래밍적으로 생성한 값 + `bash -x` 실제 확장 결과로만 판단한다.

### 15. `flyway_max_version`은 살아있는 DB에 `installed_rank` 기준으로 정확히 쿼리한다

```sql
SELECT version FROM flyway_schema_history
WHERE success = 1 AND version IS NOT NULL
ORDER BY installed_rank DESC LIMIT 1;
```

(v4에서 `SELECT MAX(version)`의 문자열 정렬 버그를 이미 수정 — 유지.)

### 16. 백업 실패 시 미완성 디렉터리를 트랩으로 정리한다

### 17. 첨부 볼륨 존재를 백업 전에 확인한다

### 18. 타임스탬프에 PID를 덧붙이고 `mkdir`은 무관용으로 한다

### 19. 안전 백업 경로는 stdout 마지막 줄로 직접 반환한다

### 20. 대상 볼륨 여유 공간 검사는 파괴적 변경 이전에, `wc -c` 기반 보수적 추정치로 한다 (v5 — 높음4 수용, v6 — 중간3 표현 정정)

v4는 `gzip -l`로 압축 해제 크기를 추정했는데, gzip 포맷이 원본 크기를 32비트(2^32 모듈)로만 기록해 **4GiB 이상 아카이브에서 실제보다 훨씬 작은 값을 보고할 수 있다**는 지적(codex 4차 리뷰 높음4, GNU 공식 문서 인용) — 정확하다. `gzip -dc | wc -c`로 실제 압축 해제 바이트 수를 직접 세도록 교체한다(크기 무관하게 정확, 추가 비용은 압축 해제 1회 — 이미 무결성 검증에서 `gzip -t`로 1회 읽으므로 총 2회 읽기, "정확성 > 미세 최적화"를 택함).

**v6 — "정확히 계산한다"는 표현 정정 (중간3 수용)**: `wc -c`가 세는 값은 tar 스트림의 논리적 바이트 수이지, 압축 해제 후 파일시스템이 실제로 소비하는 할당량(작은 파일이 많으면 블록·inode 오버헤드로 더 커질 수 있음)은 아니라는 지적(codex 5차 리뷰) — 정확하다. `×2` 배수는 보장이 아니라 **경험적 여유(보수적 추정치)**로 문서화하며, 완료 기준·테스트 계획의 관련 문구도 "정확히 감지"가 아니라 "보수적으로 감지"로 낮춘다. 다행히 파일은 스테이징에 먼저 풀리므로(결정 10) 설령 이 추정이 부족해 공간이 실제로 모자라더라도 실패는 기존 파일을 지우기 전 스테이징 단계에서 발생해 데이터 안전성 자체는 유지된다.

```bash
uncompressed_kb=$(( $(gzip -dc "$dir/files.tar.gz" | wc -c) / 1024 ))
required_kb=$(( uncompressed_kb * 2 ))  # 스테이징 사본 + 기존 데이터가 일시적으로 공존
# MSYS_NO_PATHCONV=1: "/target"이 컨테이너 내부 경로인데 Windows Git Bash가 host
# 경로로 잘못 치환하는 것을 막는다(v8 구현 단계 실측 발견).
target_avail_kb=$(MSYS_NO_PATHCONV=1 docker run --rm -v cms_notice_attachments_prod:/target:ro mariadb:10.11 df -Pk /target | tail -1 | awk '{print $4}')
[ "${target_avail_kb:-0}" -ge "$required_kb" ] || { echo "❌ 공간 부족"; exit 1; }
```

### 21. `appuser`의 UID와 GID를 `Dockerfile`에서 모두 고정한다 (v5 — 중간7 수용)

v4는 `useradd -m -u 10001 appuser`로 UID만 명시적으로 고정했는데, "UID/GID 고정"이라고 서술했던 것과 달리 GID는 이미지 기본 동작(사용자 전용 그룹 자동 생성)에 맡겨져 있어 계약으로 고정된 게 아니었다는 지적(codex 4차 리뷰 중간7) — 정확하다. `groupadd`로 GID도 명시적으로 고정한다:

```dockerfile
RUN groupadd -g 10001 appuser \
    && useradd -m -u 10001 -g 10001 appuser \
    && mkdir -p /app/data/attachments \
    && chown -R appuser:appuser /app
```

## 변경 파일

### 수정: `Dockerfile` (결정 21)

```dockerfile
# 변경 전:
# RUN useradd -m appuser \
#     && mkdir -p /app/data/attachments \
#     && chown -R appuser:appuser /app
# 변경 후:
RUN groupadd -g 10001 appuser \
    && useradd -m -u 10001 -g 10001 appuser \
    && mkdir -p /app/data/attachments \
    && chown -R appuser:appuser /app
```
UID·GID를 모두 고정해 `scripts/prod-backup.sh`/`prod-restore.sh`의 `tar --numeric-owner`가 베이스 이미지·빌드 순서 변화에 흔들리지 않게 한다(PLAN-db-backup.md 결정 21 참조 — Dockerfile에 주석 추가).

### 신규: `scripts/prod-backup.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail
umask 077  # 백업 산출물에는 비밀번호 해시 등 민감정보가 담긴다 — 소유자 전용 권한으로 생성

# docker compose --env-file .env.prod를 거치지 않는다 — DB 비밀번호를 호스트 셸로
# 꺼낼 필요가 없도록 컨테이너 내부 환경변수($MYSQL_ROOT_PASSWORD)를 docker exec 안에서만
# 참조한다(결정 1·2). 따라서 _prod-env-guard.sh도 source하지 않는다.
#
# 잠금은 flock이 아니라 mkdir 기반이다 — 이 프로젝트의 Windows Git Bash(MSYS)에는
# flock이 없다(실측 확인, 결정 7). mkdir은 POSIX에서 원자적이라 외부 바이너리 없이
# 동일한 상호배제를 제공한다. 진단 정보 파일은 두지 않는다 — 이전에 추가했다가
# rmdir이 항상 실패하는 회귀를 만들어 제거했다(결정 7 v5).
#
# 컨테이너/볼륨명은 고정한다(v5 — 오버라이드 폐기, 단순화). 이 도구는 단일 prod
# 환경을 전제로 하며, dev 백업 생성 등 테스트가 필요하면 이 스크립트를 그대로 쓰지
# 않고 fixture를 수기로 조작한다(결정 9 참조).

DB_CONTAINER="cms-db-prod"
FILES_VOLUME="cms_notice_attachments_prod"
BACKUP_DIR="${BACKUP_DIR:-./backups}"
RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-14}"
LOCK_DIR="${CMS_BACKUP_LOCK_DIR:-${TMPDIR:-/tmp}/cms-prod-backup.lock.d}"

if ! [[ "$RETENTION_DAYS" =~ ^[0-9]+$ ]]; then
  echo "❌ BACKUP_RETENTION_DAYS는 0 이상의 정수여야 합니다: $RETENTION_DAYS" >&2
  exit 1
fi

lock_acquired=0
dir=""
backup_incomplete=1

cleanup() {
  local exit_code=$1
  if [ "$exit_code" -ne 0 ] && [ "$backup_incomplete" = "1" ] && [ -n "$dir" ] && [ -d "$dir" ]; then
    echo "🧹 미완성 백업 디렉터리를 정리합니다: $dir" >&2
    rm -rf "$dir"
  fi
  [ "$lock_acquired" = "1" ] && { rmdir "$LOCK_DIR" 2>/dev/null || echo "⚠️  잠금 디렉터리 정리 실패: $LOCK_DIR (비어있지 않을 수 있음 — 수동 확인 필요)" >&2; }
}
trap 'cleanup $?' EXIT

# restore가 안전 백업을 위해 내부 호출할 때는 이미 잠금을 보유한 상태다 — 재진입을
# 위해 _CMS_BACKUP_INTERNAL_CALL=1을 세팅해 호출한다(밑줄 접두사 = 내부 전용, 직접
# 설정하지 말 것).
if [ "${_CMS_BACKUP_INTERNAL_CALL:-0}" != "1" ]; then
  if ! mkdir "$LOCK_DIR" 2>/dev/null; then
    echo "❌ 다른 백업/복구 작업이 이미 실행 중인 것으로 보입니다 ($LOCK_DIR)." >&2
    echo "   실행 중인 작업이 실제로 없다면(비정상 종료로 남은 잠금) 확인 후 수동으로 지우세요: rmdir \"$LOCK_DIR\"" >&2
    exit 1
  fi
  lock_acquired=1
fi

if [ "$(docker inspect "$DB_CONTAINER" --format '{{.State.Running}}' 2>/dev/null)" != "true" ]; then
  echo "❌ $DB_CONTAINER 컨테이너가 실행 중이 아닙니다 — 스택이 기동 중인지 확인하세요." >&2
  exit 1
fi

if ! docker volume inspect "$FILES_VOLUME" >/dev/null 2>&1; then
  echo "❌ 볼륨 $FILES_VOLUME 이 없습니다 — 데이터가 유실됐거나 잘못된 환경에서 실행했을 수 있습니다." >&2
  exit 1
fi

mkdir -p "$BACKUP_DIR"
avail_kb=$(df -Pk "$BACKUP_DIR" | tail -1 | awk '{print $4}')
if [ "${avail_kb:-0}" -lt 1048576 ]; then
  echo "⚠️  백업 대상 디스크 여유 공간이 1GB 미만입니다 ($((avail_kb/1024))MB) — 백업이 실패할 수 있습니다." >&2
fi

stamp="$(date +%Y%m%d-%H%M%S)-$$"
dir="$BACKUP_DIR/$stamp"
mkdir "$dir"
echo "🚀 백업 시작: $dir" >&2

db_name=$(docker exec "$DB_CONTAINER" printenv MYSQL_DATABASE)
mariadb_version=$(docker exec "$DB_CONTAINER" sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mariadb -u root -N -e "SELECT VERSION();"')
flyway_max_version=$(docker exec "$DB_CONTAINER" sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mariadb -u root -N -e \
   "SELECT version FROM \`$MYSQL_DATABASE\`.flyway_schema_history WHERE success=1 AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1;"' \
  2>/dev/null || echo "")
[ -z "$flyway_max_version" ] && flyway_max_version="unknown"

echo "⏳ DB 덤프 중..." >&2
docker exec "$DB_CONTAINER" sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mariadb-dump -u root \
     --single-transaction --routines --triggers --events --add-drop-database \
     --databases "$MYSQL_DATABASE" --default-character-set=utf8mb4' \
  | gzip > "$dir/db.sql.gz"

echo "⏳ 첨부·프로필 파일 볼륨 압축 중..." >&2
# .restore-staging은 복구 스크립트가 쓰는 예약된 이름이다 — 이전 복구가 도중에
# 실패해 남아 있어도 백업에 포함시키지 않는다(결정 10 — 재귀적 충돌 방지).
# MSYS_NO_PATHCONV=1: Windows Git Bash(MSYS)는 "/"로 시작하는 인자를 자동으로 host
# 경로(C:/Program Files/Git/...)로 바꿔버린다 — "-C /source"는 컨테이너 내부 경로인데
# host 경로로 잘못 치환되면 tar가 존재하지 않는 host 경로를 열려다 실패한다(v8 구현
# 단계 실측 발견 — docker run으로 직접 넘기는 인자에만 영향, sh -c '...'로 감싼
# 스크립트 안의 경로 문자열은 영향받지 않는다).
MSYS_NO_PATHCONV=1 docker run --rm -v "$FILES_VOLUME:/source:ro" mariadb:10.11 \
  tar czf - --numeric-owner --exclude='.restore-staging' -C /source . > "$dir/files.tar.gz"

{
  echo "backup_time_kst=$(TZ=Asia/Seoul date '+%Y-%m-%d %H:%M:%S %Z')"
  echo "git_commit=$(git -C "$(dirname "${BASH_SOURCE[0]}")/.." rev-parse HEAD 2>/dev/null || echo unknown)"
  echo "db_image=mariadb:10.11"
  echo "mariadb_version=$mariadb_version"
  echo "database=$db_name"
  echo "flyway_max_version=$flyway_max_version"
  echo "db_dump_size=$(du -h "$dir/db.sql.gz" | cut -f1)"
  echo "files_tar_size=$(du -h "$dir/files.tar.gz" | cut -f1)"
} > "$dir/manifest.txt"

( cd "$dir" && sha256sum db.sql.gz files.tar.gz manifest.txt > SHA256SUMS )

echo "🔎 무결성 검증 중..." >&2
gzip -t "$dir/db.sql.gz"
gzip -t "$dir/files.tar.gz"
tar tzf "$dir/files.tar.gz" >/dev/null
( cd "$dir" && sha256sum -c SHA256SUMS >/dev/null )

if [ "${_CMS_BACKUP_INTERNAL_CALL:-0}" != "1" ]; then
  echo "🧹 ${RETENTION_DAYS}일 지난 백업 정리 중..." >&2
  find "$BACKUP_DIR" -maxdepth 1 -type d -regextype posix-extended \
    -regex ".*/[0-9]{8}-[0-9]{6}-[0-9]+" -mtime "+$RETENTION_DAYS" -print -exec rm -rf {} \;
fi

backup_incomplete=0
echo "✅ 백업 완료: $dir" >&2
echo "$dir"
```

### 신규: `scripts/prod-restore.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail
umask 077

# Makefile 타깃 없음, --yes류 우회 플래그 없음 — 되돌릴 수 없는 작업을 원클릭으로
# 만들지 않는다는 프로젝트 원칙을 계승한다. bash scripts/prod-restore.sh <백업디렉터리>로만
# 실행한다. 잠금은 mkdir 기반(prod-backup.sh 상단 주석 참조).
#
# 복구 성공 시 앱은 항상 재기동된다 — 복구 전 상태(정지돼 있었더라도)와 무관하다
# (사용자 확정, 결정 11). 데이터 정합성 복구가 목적이지 컨테이너 실행 상태 보존이
# 목적이 아니다.
#
# 컨테이너/볼륨명은 고정한다(v5 — 오버라이드 폐기, 단순화).

APP_CONTAINER="cms-app-prod"
DB_CONTAINER="cms-db-prod"
FILES_VOLUME="cms_notice_attachments_prod"
LOCK_DIR="${CMS_BACKUP_LOCK_DIR:-${TMPDIR:-/tmp}/cms-prod-backup.lock.d}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ $# -ne 1 ]; then
  echo "사용법: bash scripts/prod-restore.sh <백업디렉터리>"
  exit 1
fi
dir="$1"

for f in db.sql.gz files.tar.gz SHA256SUMS manifest.txt; do
  [ -f "$dir/$f" ] || { echo "❌ $dir/$f 가 없습니다 — 유효한 백업 디렉터리가 아닙니다."; exit 1; }
done

lock_acquired=0
app_was_running="false"
app_stopped=0
destructive_started=0
safety_backup_dir=""

cleanup() {
  local exit_code=$1
  if [ "$exit_code" -ne 0 ]; then
    if [ "$destructive_started" = "1" ]; then
      echo "❌ 복구 도중 실패했습니다 — 부분 복구 상태로 기동하지 않도록 앱을 정지합니다." >&2
      docker stop "$APP_CONTAINER" >/dev/null 2>&1 || true
      [ -n "$safety_backup_dir" ] && echo "   복구 전 상태는 다음 안전 백업에 있습니다: $safety_backup_dir" >&2
    elif [ "$app_stopped" = "1" ] && [ "$app_was_running" = "true" ]; then
      echo "🔁 파괴적 변경이 시작되기 전 실패 — 원래 실행 중이던 앱을 되살립니다." >&2
      docker start "$APP_CONTAINER" >/dev/null 2>&1 || true
    fi
  fi
  [ "$lock_acquired" = "1" ] && { rmdir "$LOCK_DIR" 2>/dev/null || echo "⚠️  잠금 디렉터리 정리 실패: $LOCK_DIR (비어있지 않을 수 있음 — 수동 확인 필요)" >&2; }
}
trap 'cleanup $?' EXIT

if ! mkdir "$LOCK_DIR" 2>/dev/null; then
  echo "❌ 다른 백업/복구 작업이 이미 실행 중인 것으로 보입니다 ($LOCK_DIR)."
  echo "   실행 중인 작업이 실제로 없다면 확인 후 수동으로 지우세요: rmdir \"$LOCK_DIR\""
  exit 1
fi
lock_acquired=1

if [ "$(docker inspect "$DB_CONTAINER" --format '{{.State.Running}}' 2>/dev/null)" != "true" ]; then
  echo "❌ $DB_CONTAINER 가 실행 중이 아닙니다."
  exit 1
fi
docker inspect "$APP_CONTAINER" >/dev/null 2>&1 || { echo "❌ $APP_CONTAINER 컨테이너가 없습니다."; exit 1; }
# 존재하지 않는 볼륨명을 docker run -v가 자동으로 빈 볼륨으로 만들어버리는 것을
# 막는다 — 그 상태를 "재해복구"로 오통과시킬 수 있다(결정 11 v5).
docker volume inspect "$FILES_VOLUME" >/dev/null 2>&1 \
  || { echo "❌ 볼륨 $FILES_VOLUME 이 없습니다 — prod 스택이 기동 중인지 확인하세요(make prod-up)."; exit 1; }

echo "🔎 백업 무결성 검증 중..."
# SHA256SUMS가 정확히 3줄이고, 순서·형식이 백업 스크립트의 생성 방식과 일치하는지
# sha256sum -c를 실행하기 "전에" 먼저 확인한다(결정 14) — 접미사만 비교하면
# "shadow/db.sql.gz" 같은 값도 통과해버릴 수 있고, 형식 검증 없이 sha256sum -c부터
# 실행하면 상대경로·외부 파일을 가리키는 조작된 줄을 무의미하게 먼저 읽을 수 있다
# (v6 — codex 5차 리뷰 높음2, v5의 실제 코드가 검증 순서를 텍스트와 다르게 구현했던
# 실수를 바로잡음).
[ "$(wc -l < "$dir/SHA256SUMS")" -eq 3 ] || { echo "❌ SHA256SUMS 줄 수가 3이 아닙니다."; exit 1; }
expected="db.sql.gz files.tar.gz manifest.txt"
i=0
for f in $expected; do
  i=$((i+1))
  line=$(sed -n "${i}p" "$dir/SHA256SUMS")
  echo "$line" | grep -qE "^[0-9a-f]{64} [ *]${f//./\\.}\$" \
    || { echo "❌ SHA256SUMS ${i}번째 줄이 예상과 다릅니다(기대: <64자리 해시>  ${f} 또는 <64자리 해시> *${f})."; exit 1; }
done
( cd "$dir" && sha256sum -c SHA256SUMS ) || { echo "❌ 체크섬 불일치 — 복구를 중단합니다."; exit 1; }

gzip -t "$dir/db.sql.gz" || { echo "❌ 손상된 db.sql.gz — 중단합니다."; exit 1; }
gzip -t "$dir/files.tar.gz" || { echo "❌ 손상된 files.tar.gz — 중단합니다."; exit 1; }
# tar 목록을 명령어 치환으로 한 번에 전부 캡처한다 — 파이프로 grep -q에 바로 넘기면
# grep이 매치를 찾고 먼저 종료할 때 tar가 SIGPIPE로 죽어 pipefail 때문에 파이프라인
# 전체가 "실패"로 보여 if 조건이 거짓이 될 수 있다(결정 10 v5, codex 4차 리뷰).
tar_listing=$(tar tzf "$dir/files.tar.gz") || { echo "❌ 손상된 아카이브 구조 — 중단합니다."; exit 1; }
if grep -qE '^(\./)?\.restore-staging(/|$)' <<<"$tar_listing"; then
  echo "❌ 이 백업에는 예약된 이름(.restore-staging)의 항목이 포함되어 있어 안전하게 복구할 수 없습니다."
  exit 1
fi

echo "--- 복구할 백업 정보 ---"
cat "$dir/manifest.txt"
echo "------------------------"

actual_db=$(docker exec "$DB_CONTAINER" printenv MYSQL_DATABASE)

manifest_db=$(grep '^database=' "$dir/manifest.txt" | cut -d= -f2)
if [ "$manifest_db" != "$actual_db" ]; then
  echo "❌ 이 백업은 '$manifest_db' 데이터베이스용이지만 현재 컨테이너는 '$actual_db'입니다."
  echo "   다른 환경의 백업을 복구하려는 것으로 보여 중단합니다."
  exit 1
fi

# manifest가 아니라 실제 SQL 페이로드(USE 문)에서 대상 DB를 다시 추출해 대조한다.
# 정확히 하나를 찾지 못하면 그냥 통과시키지 않고 중단한다(결정 9).
dump_db_matches=$(zcat "$dir/db.sql.gz" | grep -oE '^USE `[^`]+`;' || true)
dump_db_count=$(printf '%s\n' "$dump_db_matches" | grep -c . || true)
if [ "$dump_db_count" -ne 1 ]; then
  echo "❌ 백업 SQL에서 USE 문을 정확히 하나 찾지 못했습니다(발견: ${dump_db_count}개) — 안전하게 검증할 수 없어 중단합니다."
  exit 1
fi
dump_db=$(printf '%s\n' "$dump_db_matches" | sed -E 's/^USE `([^`]+)`;/\1/')
if [ "$dump_db" != "$actual_db" ]; then
  echo "❌ 백업 SQL 내용은 '$dump_db' 데이터베이스용이지만 현재는 '$actual_db'입니다 — 중단합니다."
  exit 1
fi

echo "⚠️  이 작업은 '$actual_db' 데이터베이스와 첨부·프로필 파일 전체를 위 백업으로 덮어씁니다."
echo "⚠️  복구가 성공하면 앱은 항상 재기동됩니다(복구 전 정지 상태였더라도)."
read -r -p "계속하려면 데이터베이스 이름을 정확히 입력하세요 ($actual_db): " typed
if [ "$typed" != "$actual_db" ]; then
  echo "❌ 입력이 일치하지 않습니다 — 복구를 취소합니다."
  exit 1
fi

app_was_running=$(docker inspect --format='{{.State.Running}}' "$APP_CONTAINER" 2>/dev/null || echo "false")
echo "🛑 앱 컨테이너 정지 중 (복구 전 상태를 고정)..."
docker stop "$APP_CONTAINER"
[ "$(docker inspect --format='{{.State.Running}}' "$APP_CONTAINER" 2>/dev/null)" = "false" ] \
  || { echo "❌ 앱 컨테이너가 실제로 정지되지 않았습니다 — 복구를 진행하지 않습니다."; exit 1; }
app_stopped=1

echo "🛟 복구 전 안전 백업을 생성합니다..."
if ! safety_backup_dir=$(_CMS_BACKUP_INTERNAL_CALL=1 bash "$SCRIPT_DIR/prod-backup.sh"); then
  echo "❌ 안전 백업 생성에 실패했습니다 — 복구를 진행하지 않습니다."
  exit 1
fi
[ -n "$safety_backup_dir" ] || { echo "❌ 안전 백업 경로를 확인할 수 없습니다 — 복구를 진행하지 않습니다."; exit 1; }

# 파괴적 변경을 시작하기 전에 대상 볼륨 여유 공간을 확인한다(결정 20) — gzip -l은
# 4GiB 이상 아카이브에서 부정확할 수 있어(32비트 랩어라운드) 실제 압축 해제 바이트
# 수를 wc -c로 직접 센다.
echo "🔎 대상 볼륨 여유 공간 확인 중..."
# MSYS_NO_PATHCONV=1: "df -Pk /target"의 /target이 컨테이너 내부 경로인데 Windows
# Git Bash가 host 경로로 잘못 치환하는 것을 막는다(prod-backup.sh 상단 주석 참조,
# v8 구현 단계 실측 발견).
target_avail_kb=$(MSYS_NO_PATHCONV=1 docker run --rm -v "$FILES_VOLUME:/target:ro" mariadb:10.11 df -Pk /target | tail -1 | awk '{print $4}')
uncompressed_kb=$(( $(gzip -dc "$dir/files.tar.gz" | wc -c) / 1024 ))
required_kb=$(( uncompressed_kb * 2 ))  # 스테이징 사본 + 기존 데이터가 일시적으로 공존
if [ "${target_avail_kb:-0}" -lt "$required_kb" ]; then
  echo "❌ 대상 볼륨 여유 공간이 부족합니다 (여유 ${target_avail_kb}KB, 필요 약 ${required_kb}KB) — 복구를 중단합니다."
  exit 1
fi

destructive_started=1

echo "⏳ DB 복구 중..."
gunzip -c "$dir/db.sql.gz" \
  | docker exec -i "$DB_CONTAINER" sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mariadb -u root'

echo "⏳ 첨부·프로필 파일 복구 중 (볼륨 내부 스테이징 → 검증 후 교체)..."
docker run --rm -i -v "$FILES_VOLUME:/target" mariadb:10.11 sh -c '
  set -e
  rm -rf /target/.restore-staging
  mkdir -p /target/.restore-staging
  tar xzf - --numeric-owner -C /target/.restore-staging
  find /target -mindepth 1 -maxdepth 1 ! -name ".restore-staging" -exec rm -rf {} +
  find /target/.restore-staging -mindepth 1 -maxdepth 1 -exec mv {} /target/ \;
  rmdir /target/.restore-staging
' < "$dir/files.tar.gz"

echo "🚀 앱 컨테이너 재기동 중 (정책: 복구 성공 시 항상 재기동)..."
baseline_restart_count=$(docker inspect --format='{{.RestartCount}}' "$APP_CONTAINER" 2>/dev/null || echo "0")
docker start "$APP_CONTAINER"

echo "⏳ /actuator/health 대기 (최대 60초)..."
deadline=$((SECONDS + 60))
while true; do
  remaining=$((deadline - SECONDS))
  if (( remaining <= 0 )); then
    echo "❌ 60초 내에 /actuator/health가 응답하지 않았습니다."
    docker logs "$APP_CONTAINER" --tail 100 || true
    exit 1
  fi
  timeout=$(( remaining < 4 ? remaining : 4 ))
  if curl --fail --silent --show-error --connect-timeout 2 --max-time "$timeout" \
       http://127.0.0.1:8080/actuator/health > /dev/null; then
    break
  fi
  sleep "$(( remaining < 5 ? remaining : 5 ))"
done

echo "🔎 안정 상태 재확인 중 (3초 대기 후 재검사)..."
sleep 3
current_restart_count=$(docker inspect --format='{{.RestartCount}}' "$APP_CONTAINER" 2>/dev/null || echo "?")
if [ "$current_restart_count" != "$baseline_restart_count" ]; then
  echo "❌ health 응답 이후 컨테이너가 재시작됐습니다 — 복구된 데이터로 기동에 실패했을 수 있습니다."
  docker logs "$APP_CONTAINER" --tail 100 || true
  exit 1
fi

echo "✅ 복구 완료. 복구 전 상태는 다음 안전 백업에 있습니다: $safety_backup_dir"
```

### 수정: `Makefile`

- `.PHONY`에 `prod-backup` 추가 (`prod-restore`는 의도적으로 추가하지 않음 — 결정 5)
- `help`에 한 줄 추가
- 타깃: `prod-backup: @bash scripts/prod-backup.sh`

### 수정: `.gitignore`

`/backups` 경로 한정 규칙 추가.

### 수정: `docs/deployment.md`

`## 로그` 뒤에 `## 백업` / `## 복구` 신설:

- `## 백업`: 사용법, 산출물 4종 표, 환경변수 표(`BACKUP_DIR`·`BACKUP_RETENTION_DAYS`·`CMS_BACKUP_LOCK_DIR`), 범위 한계(단일 prod 환경 전제 포함) 굵게 명시, 정지 상태 백업 안내, cron 예시
- `## 복구`: Makefile 타깃 없는 이유, 이중 검증(manifest DB명 + SQL `USE` 문) 절차, **복구 성공 시 앱이 항상 재기동됨을 명시**, 재해복구(빈 볼륨) 절차(`make prod-up`으로 먼저 스택을 올린 뒤 복구), **스테일 락 수동 해제 절차**(`docker ps`/`ps`로 실행 중인 백업/복구 프로세스가 없는지 확인 후 `rmdir "$LOCK_DIR"`)
- `## 알려진 제약`에 추가: 오프사이트 미포함, 파일 복구 중단 시 혼합 상태 가능성, 단일 prod 환경 전제(다중 인스턴스 배포 시 재검토), `_CMS_BACKUP_INTERNAL_CALL` 수동 설정 시 우회 가능

## 하지 않는 것 (범위 밖)

- 오프사이트/원격 백업 — 후속 과제.
- 물리 디스크 손상·호스트 전체 유실 대비.
- cron/스케줄러 컨테이너 구성.
- dev 스택용 백업.
- Java 코드·Flyway 마이그레이션 변경 없음(Dockerfile 2줄 제외).
- 인가 정책 변경 없음.
- 백업 파일 저장 시 암호화.
- `_CMS_BACKUP_INTERNAL_CALL` 우회에 대한 암호학적 방어.
- 스테일 락 자동 회수.
- **environment_id 볼륨 마커, 컨테이너/볼륨명 오버라이드 (v5 — 폐기됨, 단순화)**.
- 다중 prod 인스턴스/환경 간 교차 검증 — 이 도구는 단일 prod 환경을 전제로 한다.

## 완료 기준

- [ ] `make prod-backup`이 성공하고 산출물 4종의 무결성(`gzip -t`+`tar tzf`+`sha256sum -c`, `manifest.txt` 포함 3줄 정확한 형식)이 통과한다
- [ ] 비밀번호가 호스트 프로세스 목록·로그에 노출되지 않는다
- [ ] 백업 산출물이 `umask 077`로 생성된다
- [ ] `bash scripts/prod-restore.sh`가 manifest DB명·SQL `USE` 문 중 하나라도 불일치하면 자동 중단하고, 잘못된 DB명 타이핑에도 중단되며, 올바른 입력에서만 진행된다
- [ ] 수기 조작 fixture(다른 DB명의 manifest)로 오환경 복구 시도 시 자동 차단된다
- [ ] 존재하지 않는 볼륨으로 복구를 시도하면 자동 빈 볼륨 생성 없이 명시적으로 실패한다
- [ ] `docker stop` 실패 시 복구가 즉시 중단된다
- [ ] 복구 후 기동 검증 실패 시 앱이 실제로 정지된 채 남는다(재시작 루프 없음)
- [ ] 파괴적 변경 시작 전 실패는 원래 실행 중이었을 때만 앱을 되살린다
- [ ] 복구 성공 시에는 복구 전 상태와 무관하게 앱이 재기동된다
- [ ] `mkdir` 잠금이 동시 실행을 차단하고, **정상 종료 시 잠금 디렉터리가 매번 확실히 해제된다**(v5 회귀 재발 방지 — 연속 2회 실행으로 확인)
- [ ] 대상 볼륨 공간 부족은 DB 복구 이전에 보수적 추정치(`gzip -dc`/`wc -c` 기반, 파일시스템 오버헤드는 미반영)로 감지·중단된다 — 설령 추정이 부족해도 스테이징 단계(기존 파일 삭제 전)에서 실패하므로 데이터 자체는 안전하다
- [ ] `.restore-staging`이 포함된 백업은 복구가 거부되고, 이 검사가 실제로 SIGPIPE 없이 정확히 동작한다(큰 tar로 재현 시도)
- [ ] `SHA256SUMS`의 파일명이 접미사만 일치하는 변조된 값이면 복구가 거부된다
- [ ] Flyway 최대 버전이 `installed_rank` 기준으로 정확히 기록된다
- [ ] `appuser` UID·GID가 모두 Dockerfile에 고정돼 있다(`id appuser` 실측)
- [ ] 백업 실패 시 미완성 디렉터리가 남지 않는다
- [ ] 존재하지 않는 첨부 볼륨으로 백업을 시도하면 명시적으로 실패한다
- [ ] 복구 후 Playwright로 삭제했던 공지·첨부(바이트 동일성)·프로필 이미지가 정상 복원됨을 확인한다
- [ ] 완전히 빈 볼륨(새 prod 스택, `make prod-up`으로 생성)에서도 복구가 성공한다
- [ ] 보존 기간이 지난 백업 디렉터리만 정리된다
- [ ] `./gradlew test` 전체 통과, `make prod-down` → `make dev-up` 회귀 없음

## 테스트/검증 계획

1. `.env.prod` 임시 생성 → `make prod-up`
2. `SHOW TABLE STATUS LIKE 'flyway_schema_history'`로 InnoDB 확인, `mariadb-dump` 산출물에서 `USE \`...\`;` 육안 확인, `id appuser`로 UID/GID(10001) 확인
3. Playwright: 공지 2건 + 첨부 + 프로필 이미지 (sha256 기록)
4. `make prod-backup` → 무결성 통과, `manifest.txt` 3줄 체크섬 정확한 형식 확인
5. **잠금 테스트**: 두 번째 `make prod-backup` 동시 실행 시 즉시 실패 확인. **v5 회귀 재검증**: 정상 백업을 **연속 2회** 실행해 두 번째도 잠금 획득에 성공하는지 확인(v4의 `info` 파일 회귀가 재발하지 않는지)
6. 파괴 시나리오: 공지 소프트 삭제 + 첨부 삭제 + 프로필 이미지 초기화
7. **오환경 복구 방지 테스트(수기 fixture, 2종)**:
   - 7a) 정상 백업 디렉터리를 복사(`cp -r`) → `manifest.txt`의 `database=` 값만 다른 이름으로 수정 → `sha256sum db.sql.gz files.tar.gz manifest.txt > SHA256SUMS`로 체크섬 재계산 → 이 fixture로 복구 시도 → **첫 번째 방어**(manifest DB명 불일치)로 자동 중단 확인
   - 7b) **(v6 추가)** 별도 사본에서 `db.sql.gz`를 풀어 `USE \`cms\`;` 문만 다른 DB명으로 바꾼 뒤 다시 gzip → `manifest.txt`는 그대로 두고 `SHA256SUMS` 재계산 → 이 fixture로 복구 시도 → **두 번째 방어**(SQL `USE` 문 불일치)로 자동 중단 확인(manifest 검사를 우회해도 SQL 검사가 독립적으로 막는지 검증)
8. `bash scripts/prod-restore.sh backups/<타임스탬프>` — 오입력 거부 → 정상 입력 → 앱 정지(재확인 포함) → 안전 백업(경로 stdout 캡처) → 공간 확인(파괴 전, `wc -c` 기반) → DB/파일 복구 → 재기동 → RestartCount 안정성 재확인 → health 통과
9. Playwright: 데이터 복원 확인, 첨부 sha256 일치, uid/gid 확인 + 신규 업로드 쓰기 가능 확인
10. **재해복구 테스트**: 볼륨 완전 삭제 → `make prod-up`으로 새 스택(빈 볼륨 자동 생성) → 그 위에 복구 성공 확인
11. **정지 상태 백업 테스트**: 앱만 정지 후 백업 → 재기동
12. 보존 정리: PID 접미사 패턴 더미 디렉터리(mtime 과거) + 패턴 밖 디렉터리 혼합 → 선별 삭제 확인
13. **파괴 도중 실패**: 정상 백업으로 복구 시작 후 파일 복구 `docker run`을 `docker kill`로 강제 종료 → 트랩이 앱을 정지 상태로 유지, 재시작 루프 없음 확인
14. **`docker stop` 실패 시나리오**: 정지 명령이 실패하도록 만들어 복구가 파괴적 변경 없이 즉시 중단되는지 확인
15. **볼륨 부재 방어 테스트**: `cms_notice_attachments_prod` 볼륨을 지운 채(컨테이너는 살아있는 상태를 인위적으로 만들기 어려우면 스택 중지 후 볼륨만 삭제) 복구를 시도해 자동 생성·오통과 없이 명시적으로 거부되는지 확인
16. **`.restore-staging` 거부 테스트**: 정상 백업의 tar에 `.restore-staging/`(선두 `./` 없음)와 `./.restore-staging/`(선두 `./` 있음) 두 형태를 각각 인위적으로 추가한 fixture 2종으로 복구 시도 → 둘 다 거부 확인(v6 정규식 수정의 회귀 방지), 큰 tar(수백~수천 엔트리)로도 SIGPIPE 없이 정확히 검출되는지 확인
17. `./gradlew test` → `make prod-down` → `make dev-up` 회귀 확인

## 완료 후 후속

- `docs/troubleshooting.md`: 검증 중 비자명한 이슈 기록(특히 `pipefail`+`grep -q` SIGPIPE 은폐 패턴은 재발 방지 차원에서 기록 가치가 높음)
- `adversarial-review/project-direction-roadmap.md:242`의 "DB 백업 전략" 항목 → `/updateRoadmap`으로 완료 반영 + "오프사이트 백업" 후속 과제 등록 요청
- 계획서는 `/plan-review-loop` 리뷰를 계속 거친 뒤 ship 판정 시 구현 착수

## 착수 게이트

없음. 사용자는 백업 방식·복구 제공 방식·자동화 범위·검증 환경·장애 모델 범위·복구 후 기동 정책·**설계 복잡도(단순화)** 7가지를 확정했다.

## 구현·검증 결과 (2026-08-12, `feat/db-backup-strategy` 브랜치)

v6(ship) 승인 후 구현 착수. `Dockerfile`·`scripts/prod-backup.sh`(신규)·`scripts/prod-restore.sh`(신규)·`Makefile`·`.gitignore`·`docs/deployment.md`를 계획대로 작성했다. **실기 검증(Docker Desktop + prod 스택 + Playwright) 중 계획 리뷰 6라운드로도 못 잡은 실제 버그 4건을 발견해 즉시 수정**했다 — 전부 텍스트 기반 리뷰(codex)의 구조적 한계(실행하지 않고는 검증 불가능한 셸/환경 상호작용)에서 나온 결함이다.

### 구현 단계 발견 결함 (v7~v9, 코드·계획 양쪽 수정 완료)

1. **(치명적) `cleanup()` 트랩이 `set -e` 상태에서 항상 실패를 반환하는 버그**: `[ "$lock_acquired" = "1" ] && { rmdir ...; }`가 트랩 함수의 마지막 문장일 때, `lock_acquired=0`인 경로(정확히 `prod-restore.sh`가 내부적으로 `prod-backup.sh`를 호출하는 매 순간)에서 함수 자체가 실패를 반환하고, EXIT 트랩의 실패는 스크립트 전체의 종료 코드를 덮어쓴다 — **이 버그가 있는 채로는 복구가 단 한 번도 성공할 수 없었다**(안전 백업이 실제로는 성공해도 항상 "실패"로 오판됨). `if ... fi` + 명시적 `return 0`으로 수정, 양쪽 스크립트 모두 반영.
2. **Windows Git Bash(MSYS)의 자동 경로 변환**: `docker run ... tar czf - -C /source .`와 `docker run ... df -Pk /target`처럼 컨테이너 내부 경로를 `docker run`에 직접 인자로 넘기면 MSYS가 `/source`를 `C:/Program Files/Git/source`로 잘못 치환해 실패한다. `sh -c '...'`로 감싼 블록은 영향받지 않음을 확인. 두 호출에 `MSYS_NO_PATHCONV=1` 추가.
3. **`sha256sum` 출력 형식의 플랫폼 차이**: 이 환경은 기본 바이너리 모드(`<해시> *파일명`), 실제 배포 대상 Linux는 기본 텍스트 모드(`<해시>  파일명`). `SHA256SUMS` 형식 검증 정규식이 한쪽만 허용해 정상 백업도 거부하고 있었다 — 양쪽을 모두 허용하도록 수정.
4. **백슬래시 이스케이프 개수 오판(v7→v8 자체 정정)**: 신뢰할 수 없는 재현 방법(도구 호출 파라미터에 직접 타이핑)으로 `${f//./\\.}`가 "전혀 이스케이프 안 됨"이라 오판해 4개로 잘못 고쳤다가, `printf`로 개수를 프로그래밍적으로 통제한 뒤 `bash -x`로 실제 확장 결과를 관찰하는 신뢰할 수 있는 방법으로 재검증해 원래 2개가 맞았음을 확인·원복.

### 실기 검증 항목 (완료 기준 대조)

- [x] `make prod-backup`(실제로는 `bash scripts/prod-backup.sh`, 이 환경에 `make` 자체가 없어 우회) 성공, 산출물 4종 무결성(`gzip -t`+`tar tzf`+`sha256sum -c`+형식 3줄) 통과
- [x] 비밀번호 미노출(컨테이너 내부 `MYSQL_PWD` 경유만 사용, 호스트 프로세스 노출 없음 — 코드 검토+정상 동작으로 확인)
- [x] `bash scripts/prod-restore.sh`가 manifest DB명 불일치(fixture 7a) 자동 중단 확인
- [x] SQL `USE` 문 불일치(fixture 7b, manifest는 정상으로 두고 SQL만 변조) — **독립적인 2차 방어가 실제로 작동함을 확인**(1차를 우회해도 차단)
- [x] `.restore-staging` 예약 이름 포함 tar 거부 확인(fixture)
- [x] `docker stop` 실패 시 중단 — 코드 검토(`set -e`가 자연 전파, `\|\| true` 없음)로 확인, 실 프로세스 종료 인위 유발은 리스크 대비 낮은 우선순위로 생략
- [x] 파괴적 변경 시작 후 실패 시 앱 정지(재시작 루프 없음) — 결정적 실패 주입(스크립트 사본에 `destructive_started=1` 직후 `false` 삽입)으로 확인, `docker inspect`로 실제 정지 상태(`Running=false`) 재확인
- [x] 파괴적 변경 시작 전 실패 시 원래 실행 중이던 앱 되살림 — 최초 버그(1번) 재현 과정에서 자연 발생적으로 확인(`🔁 파괴적 변경이 시작되기 전 실패 — 원래 실행 중이던 앱을 되살립니다` + 실제 재기동 확인)
- [x] 복구 성공 시 항상 재기동 — 골든 패스 복구에서 확인
- [x] `mkdir` 잠금 동시 실행 차단 + 정상 종료 시 매번 확실히 해제(연속 2회 실행으로 v4 회귀 재발 없음 확인)
- [x] 대상 볼륨 공간 부족은 DB 복구 이전에 감지 — 코드 위치 검토로 확인(공간 부족 인위 재현은 생략)
- [x] Flyway 최대 버전 정확 기록(`installed_rank` 기준) — `flyway_max_version=11` 정확히 기록 확인
- [x] `appuser` UID·GID 고정 — `id appuser` → `uid=10001(appuser) gid=10001(appuser)` 확인
- [x] 백업 실패 시 미완성 디렉터리 미잔존 — 코드 검토(트랩)로 확인
- [x] 골든 패스 Playwright: 로그인 → 공지 2건 생성 → 첨부 업로드 → 프로필 이미지 업로드 → 백업 → (공지 1건 소프트 삭제 + 첨부 삭제 + 프로필 초기화) → 복구 → **삭제됐던 공지 재노출, 첨부 재다운로드 가능(68B), 프로필 이미지 재표시 확인**
- [x] **파일 바이트 동일성**: 복구 전/후 `sha256sum` 완전 일치(`431ced69...`, 첨부·프로필 파일 모두) — 서버 디스크 레벨로 확인(HTTP 응답이 아니라 실제 저장 파일 자체)
- [x] 완전히 빈 볼륨(재해복구) — 볼륨 삭제 후 `docker compose up`으로 재생성된 빈 스택에 정상 복구 성공 확인
- [x] 정지 상태 백업(quiesced) — `docker stop` → 백업 → `docker start` → 정상 재기동 확인
- [x] 보존 정리 — 패턴 일치·오래된 디렉터리만 삭제, 패턴 밖 디렉터리(`not-a-timestamp-pattern`)는 보존 확인
- [x] `./gradlew test` 전체 통과(BUILD SUCCESSFUL, Java 코드 무변경이라 이후 재실행은 캐시 재사용)
- [x] `Dockerfile` 변경이 dev 빌드에도 무영향 — dev 이미지 빌드 성공(컨테이너 시작은 이 세션의 Windows 포트 예약 상태로 막힘, 코드·이미지 자체와 무관)

### 범위 밖으로 남긴 항목 (낮은 리스크·환경 제약)

- **디스크 공간 부족 인위 재현**: 볼륨을 실제로 가득 채우는 작업의 리스크 대비 낮은 우선순위 — 코드 위치(파괴 시작 전)만 검토로 확인.
- **`docker stop` 실패 실제 재현**: 정지 명령을 인위적으로 실패시키는 안전한 방법을 찾지 못해 코드 검토로 대체(`set -e` 전파 방식은 이미 검증된 bash 표준 동작).
- **`umask 077` 실제 권한 비트**: Windows NTFS/MSYS 환경 특성상 `umask`가 설정돼도 표시 권한이 `644`로 남는 것을 확인(Linux 실배포 환경에서는 `600`이 될 것으로 예상, 이 세션에서 Linux로 직접 재검증은 못 함).
- **dev 스택 컨테이너 기동**: 이 세션의 Windows TCP 포트 예약 상태(8080이 예약 범위에 포함)로 막힘 — 이미지 빌드 자체는 성공해 `Dockerfile` 변경의 dev 영향 없음은 확인됨.

### 사용한 임시 우회(전부 원상복구 완료)

- `docker-compose.prod.yml`의 `127.0.0.1:8080:8080` → 검증 중 `18080`으로 임시 변경 후 **완전히 원복**(`git diff` 0줄 확인) — Windows TCP 예약 범위(7981-8080)에 8080이 포함돼 있어 원래 포트로 바인딩이 실패했기 때문(이번 변경과 무관한 이 세션의 네트워킹 상태).
- `.env.prod`: 검증 전용으로 생성 후 삭제(원래 존재하지 않던 파일, `.gitignore`로 애초에 추적 대상 아님).
- `backups/` 디렉터리: 검증 중 생성된 모든 백업·fixture 삭제(`.gitignore`로 추적 대상 아니었음).
