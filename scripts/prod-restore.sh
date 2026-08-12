#!/usr/bin/env bash
set -euo pipefail
umask 077

# Makefile 타깃 없음, --yes류 우회 플래그 없음 — 되돌릴 수 없는 작업을 원클릭으로
# 만들지 않는다는 프로젝트 원칙을 계승한다(scripts/prod-down.sh:12-15, PLAN-prod-profile.md
# 결정 8). bash scripts/prod-restore.sh <백업디렉터리>로만 실행한다. 잠금은 mkdir
# 기반(prod-backup.sh 상단 주석 참조).
#
# 복구 성공 시 앱은 항상 재기동된다 — 복구 전 상태(정지돼 있었더라도)와 무관하다
# (사용자 확정, PLAN-db-backup.md 결정 11). 데이터 정합성 복구가 목적이지 컨테이너
# 실행 상태 보존이 목적이 아니다.
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
  if [ "$lock_acquired" = "1" ]; then
    rmdir "$LOCK_DIR" 2>/dev/null || echo "⚠️  잠금 디렉터리 정리 실패: $LOCK_DIR (비어있지 않을 수 있음 — 수동 확인 필요)" >&2
  fi
  # set -e 상태에서 EXIT 트랩의 마지막 명령이 실패로 끝나면 그 실패가 스크립트
  # 전체의 종료 코드를 덮어써버린다(구현 단계 실측 발견 — 이 버그 때문에 정상
  # 완료된 안전 백업 호출이 항상 "실패"로 오판돼 복구 자체가 불가능했었다).
  # 트랩은 항상 성공으로 끝내 원래 종료 코드가 그대로 전파되게 한다.
  return 0
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
# 실행하면 상대경로·외부 파일을 가리키는 조작된 줄을 무의미하게 먼저 읽을 수 있다.
[ "$(wc -l < "$dir/SHA256SUMS")" -eq 3 ] || { echo "❌ SHA256SUMS 줄 수가 3이 아닙니다."; exit 1; }
expected="db.sql.gz files.tar.gz manifest.txt"
i=0
for f in $expected; do
  i=$((i+1))
  line=$(sed -n "${i}p" "$dir/SHA256SUMS")
  # sha256sum 출력 형식은 플랫폼마다 다르다 — GNU coreutils는 텍스트 모드일 때
  # "<해시>  파일명"(스페이스 2개), 바이너리 모드일 때 "<해시> *파일명"(스페이스+별표)를
  # 쓴다. Windows Git Bash(MSYS)는 기본이 바이너리 모드, 실제 배포 대상인 Linux는
  # 기본이 텍스트 모드라 두 형식을 모두 허용해야 한다(구현 단계 실측 발견).
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
# 수를 wc -c로 직접 센다. 배수는 보장이 아니라 보수적 추정치다(파일시스템 오버헤드
# 미반영) — 부족해도 스테이징 단계(기존 파일 삭제 전)에서 실패하므로 데이터는 안전.
echo "🔎 대상 볼륨 여유 공간 확인 중..."
# MSYS_NO_PATHCONV=1: "df -Pk /target"의 /target이 컨테이너 내부 경로인데 Windows
# Git Bash가 host 경로로 잘못 치환하는 것을 막는다(prod-backup.sh 상단 주석 참조,
# 구현 단계 실측 발견).
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
# RestartCount만으로는 "재시작 없이 DOWN"인 경우(예: 복구 직후 DB 연결 상실)를
# 놓친다 — prod-up.sh와 동일하게 health를 한 번 더 직접 찔러 확인한다(codex 리뷰 지적).
if ! curl --fail --silent --show-error --connect-timeout 2 --max-time 4 \
     http://127.0.0.1:8080/actuator/health > /dev/null; then
  echo "❌ 안정성 재확인에서 /actuator/health가 실패했습니다 — 초기 200 응답이 이후 실패 직전의 일시적 상태였을 수 있습니다."
  docker logs "$APP_CONTAINER" --tail 100 || true
  exit 1
fi

echo "✅ 복구 완료. 복구 전 상태는 다음 안전 백업에 있습니다: $safety_backup_dir"
