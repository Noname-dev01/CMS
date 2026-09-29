#!/usr/bin/env bash
# 백업 → 변경 → 복구 왕복 게이트 — 실제 prod-backup.sh / prod-restore.sh를 그대로 호출한다.
# 계획: adversarial-review/plan/PLAN-ci-prod-gates.md 쟁점 2 (M-06)
#
# prod-restore.sh의 대화형 확인은 우회하지 않는다 — DB 이름을 표준입력으로 전달해 그 관문을 통과한다.
# 이 시험은 "바이트·소유권·DB 행 복원"까지만 증명하며, 앱 레벨 첨부 조회는 수동 drill
# (docs/verification/recovery-drill.md)의 범위다 — drill을 대체하지 않는다.
#
# ⚠️ 폐기 가능한 Docker 환경 전용. 단독 실행 시 스택을 스스로 띄우고 정리하며,
# prod-smoke.sh에서 호출되면(CMS_CI_STACK_UP=1) 이미 떠 있는 스택을 재사용한다.
set -euo pipefail

# shellcheck source=./_prod-ci-common.sh
source "$(dirname "${BASH_SOURCE[0]}")/_prod-ci-common.sh"
ci_enter_repo_root

if [ "${CMS_CI_STACK_UP:-0}" != "1" ]; then
  ci_start_stack
  work="$CI_TMP_DIR"
else
  # 환경변수만으로는 진입할 수 없다 — 어떤 쓰기보다 먼저 부모 실행의 잠금 토큰·합성 DB 이름을 검증한다.
  ci_require_parent_ownership
  work="${CMS_CI_TMP_DIR:?CMS_CI_TMP_DIR 필요}"
  [ -d "$work" ] || ci_fail "CMS_CI_TMP_DIR가 디렉터리가 아닙니다: $work"
fi
BACKUP_ROOT="$work/backups"
mkdir -p "$BACKUP_ROOT"

# 시험용 파일 조작에 쓰는 이미지 — prod-backup.sh가 쓰는 것과 동일한 고정 참조.
IMG=$(ci_mariadb_image)

sql() {  # 컨테이너 내부 환경변수로만 비밀번호를 참조한다(호스트 셸·프로세스 목록에 노출 없음).
  docker exec "$CI_DB_CONTAINER" sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mariadb -u root -N "$MYSQL_DATABASE" -e "$0"' "$1"
}
vol() {  # 볼륨에 대해 컨테이너 안에서 명령 실행
  MSYS_NO_PATHCONV=1 docker run --rm -v "$CI_FILES_VOLUME:/t" "$IMG" sh -c "$1"
}

# 상태 스냅샷: DB 행 + 볼륨 파일 집합(경로·sha256) + 소유권. 비교를 문자열 동치로 단순화한다.
snapshot() {
  {
    echo "[rows]"; sql "SELECT id, v FROM ci_marker ORDER BY id"
    echo "[files]"; vol 'cd /t && find ci -type f | sort | while read -r f; do echo "$f $(sha256sum "$f" | cut -d" " -f1) $(stat -c "%u:%g" "$f")"; done'
  }
}

app_running() { docker inspect --format '{{.State.Running}}' "$CI_APP_CONTAINER"; }
restart_count() { docker inspect --format '{{.RestartCount}}' "$CI_APP_CONTAINER"; }

# --- 1. 마커 심기 ---------------------------------------------------------------
sql "CREATE TABLE ci_marker (id INT PRIMARY KEY, v VARCHAR(50) NOT NULL)"
sql "INSERT INTO ci_marker VALUES (1, 'before')"
vol 'mkdir -p /t/ci && echo before > /t/ci/marker.txt && chown -R 10001:10001 /t/ci'
S0=$(snapshot)
grep -q '^ci/marker.txt .* 10001:10001$' <<<"$S0" || ci_fail "시험 준비: marker.txt 소유권이 10001:10001이 아닙니다: $S0"
grep -q '^1	before$' <<<"$S0" || ci_fail "시험 준비: DB 마커 행이 없습니다: $S0"
ci_ok "마커 준비 완료(백업 시점 상태 기록)"

# --- 2. 정규(quiesced) 백업 -----------------------------------------------------
docker stop "$CI_APP_CONTAINER" >/dev/null
[ "$(app_running)" = "false" ] || ci_fail "앱이 정지되지 않았습니다."
backup_dir=$(BACKUP_DIR="$BACKUP_ROOT" bash scripts/prod-backup.sh) || ci_fail "prod-backup.sh 실패"
docker start "$CI_APP_CONTAINER" >/dev/null
ci_wait_health
[ -d "$backup_dir" ] || ci_fail "백업 디렉터리가 없습니다: $backup_dir"
ci_ok "quiesced 백업 완료: $(basename "$backup_dir")"

# --- 3. 백업 이후 변경 ------------------------------------------------------------
sql "UPDATE ci_marker SET v='after' WHERE id=1"
sql "INSERT INTO ci_marker VALUES (2, 'extra')"
vol 'echo after > /t/ci/marker.txt && chown root:root /t/ci/marker.txt && echo extra > /t/ci/extra.txt'
S1=$(snapshot)
# 변경이 실제로 반영됐음을 복구 직전에 단언 — 변경 자체가 실패했는데 "정상 복원"으로 판정되는 것을 막는다.
[ "$S1" != "$S0" ] || ci_fail "변경이 반영되지 않았습니다(스냅샷 동일)."
grep -q '^1	after$' <<<"$S1"                 || ci_fail "DB 행 변경이 반영되지 않았습니다."
grep -q '^2	extra$' <<<"$S1"                 || ci_fail "DB 추가 행이 반영되지 않았습니다."
grep -q '^ci/extra.txt ' <<<"$S1"            || ci_fail "추가 파일이 반영되지 않았습니다."
grep -q '^ci/marker.txt .* 0:0$' <<<"$S1"    || ci_fail "소유권 변경(root)이 반영되지 않았습니다."
ci_ok "백업 이후 변경이 실제로 반영됨(복구 직전 상태 확인)"

# --- 4. 음성 케이스: 잘못된 DB 이름 → 입력 불일치 사유로 거절, 상태 불변 ------------
restart_before=$(restart_count)
set +e
neg_out=$(printf 'wrong_db_name\n' | BACKUP_DIR="$BACKUP_ROOT" bash scripts/prod-restore.sh "$backup_dir" 2>&1)
neg_code=$?
set -e
[ "$neg_code" -ne 0 ] || ci_fail "잘못된 DB 이름인데 복구가 성공했습니다."
# 체크섬·잠금 등 다른 이유의 실패로 통과하지 않도록 스크립트의 실제 거절 문구까지 확인한다.
grep -q '입력이 일치하지 않습니다' <<<"$neg_out" || ci_fail "거절 사유가 입력 불일치가 아닙니다: $neg_out"
[ "$(app_running)" = "true" ] || ci_fail "거절 뒤 앱이 정지 상태입니다."
[ "$(restart_count)" = "$restart_before" ] || ci_fail "거절 뒤 앱이 재시작됐습니다."
[ "$(snapshot)" = "$S1" ] || ci_fail "거절 뒤 DB·파일 상태가 변했습니다."
ci_ok "잘못된 DB 이름 입력 거절(입력 불일치 사유) — DB·파일·앱 상태 불변"

# --- 5. 실제 복구 -----------------------------------------------------------------
printf '%s\n' "$CI_DB_NAME" | BACKUP_DIR="$BACKUP_ROOT" bash scripts/prod-restore.sh "$backup_dir" \
  || ci_fail "prod-restore.sh 실패"
ci_wait_health
S2=$(snapshot)
[ "$S2" = "$S0" ] || ci_fail "복구 후 상태가 백업 시점과 다릅니다.
--- 백업 시점(S0) ---
$S0
--- 복구 후(S2) ---
$S2"
# S2==S0이므로 아래는 의도를 드러내는 명시적 단언이다(추가 행·파일 소멸, 소유권 10001:10001, 파일 집합 동일).
grep -q '^2	extra$' <<<"$S2"         && ci_fail "백업 이후 추가한 행이 복구 뒤에도 남아 있습니다."
grep -q '^ci/extra.txt ' <<<"$S2"    && ci_fail "백업 이후 추가한 파일이 복구 뒤에도 남아 있습니다."
grep -q '^ci/marker.txt .* 10001:10001$' <<<"$S2" || ci_fail "복구 뒤 소유권이 10001:10001이 아닙니다."
[ "$(app_running)" = "true" ] || ci_fail "복구 뒤 앱이 실행 중이 아닙니다."
[ "$(ci_http_code "$CI_BASE_URL/actuator/health")" = "200" ] || ci_fail "복구 뒤 health가 200이 아닙니다."
ci_ok "복구 완료 — DB 행·파일 sha256·소유권 복원, 백업 이후 추가분 소멸, 앱 정상"

ci_ok "백업·복구 왕복 게이트 통과"
