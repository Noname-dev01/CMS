#!/usr/bin/env bash
set -euo pipefail
umask 077  # 백업 산출물에는 비밀번호 해시 등 민감정보가 담긴다 — 소유자 전용 권한으로 생성

# docker compose --env-file .env.prod를 거치지 않는다 — DB 비밀번호를 호스트 셸로
# 꺼낼 필요가 없도록 컨테이너 내부 환경변수($MYSQL_ROOT_PASSWORD)를 docker exec 안에서만
# 참조한다(PLAN-db-backup.md 결정 1·2). 따라서 _prod-env-guard.sh도 source하지 않는다.
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
dir_created=0
backup_incomplete=1

cleanup() {
  local exit_code=$1
  # dir_created(이번 실행이 mkdir로 직접 만들었는지)로 판단한다 — -d "$dir"만으로
  # 판단하면 mkdir "$dir" 자체가 "이미 존재함"으로 실패했을 때(PID 재사용 등으로
  # 기존 백업 디렉터리와 충돌) 이번 실행이 만들지도 않은 그 기존 디렉터리를
  # rm -rf로 지워버리는 데이터 유실 결함이 된다(codex 리뷰 지적).
  if [ "$exit_code" -ne 0 ] && [ "$backup_incomplete" = "1" ] && [ "$dir_created" = "1" ] && [ -n "$dir" ] && [ -d "$dir" ]; then
    echo "🧹 미완성 백업 디렉터리를 정리합니다: $dir" >&2
    rm -rf "$dir"
  fi
  if [ "$lock_acquired" = "1" ]; then
    rmdir "$LOCK_DIR" 2>/dev/null || echo "⚠️  잠금 디렉터리 정리 실패: $LOCK_DIR (비어있지 않을 수 있음 — 수동 확인 필요)" >&2
  fi
  # set -e 상태에서 EXIT 트랩의 마지막 명령이 실패로 끝나면 그 실패가 스크립트
  # 전체의 종료 코드를 덮어써버린다(구현 단계 실측 발견 — _CMS_BACKUP_INTERNAL_CALL=1로
  # 호출돼 lock_acquired가 0으로 남는 경로에서 위 if가 스킵되며 재현됨). 트랩은 항상
  # 성공으로 끝내 원래 종료 코드가 그대로 전파되게 한다.
  return 0
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

# 초 단위 타임스탬프만으로는 실패 직후 같은 초에 재시도하면 충돌할 수 있어 PID를 덧붙인다.
stamp="$(date +%Y%m%d-%H%M%S)-$$"
dir="$BACKUP_DIR/$stamp"
mkdir "$dir"   # -p를 쓰지 않는다 — 이미 존재하면(이론상 불가능) 무관용으로 실패
dir_created=1  # 이 mkdir이 성공해 이번 실행이 직접 만든 디렉터리임을 표시(cleanup 참고)
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
# 경로(C:/Program Files/Git/...)로 바꿔버린다 — 여기서 "-C /source"는 컨테이너 내부
# 경로인데 host 경로로 잘못 치환되면 tar가 존재하지 않는 host 경로를 열려다 실패한다
# (구현 단계 실측 발견 — docker run으로 직접 넘기는 인자에만 영향, sh -c '...'로
# 감싼 스크립트 안에 등장하는 경로 문자열은 영향받지 않는다).
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
