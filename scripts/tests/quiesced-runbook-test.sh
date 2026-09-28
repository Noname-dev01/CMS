#!/usr/bin/env bash
# PR6-R1 회귀: 문서의 실행 블록을 읽는다. 실제 Docker / make / curl은 호출하지 않는다.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
runbook=$(awk '
  /<!-- quiesced-backup-runbook:start -->/ { section=1; next }
  /<!-- quiesced-backup-runbook:end -->/ { section=0 }
  section && /^```/ { next }
  section { print }
' "$repo_root/docs/deployment.md")
[ -n "$runbook" ] || { echo "runbook block missing" >&2; exit 1; }

scratch=$(mktemp -d)
state_file="$scratch/state"
calls_file="$scratch/calls"
trap 'rm -f "$state_file" "$calls_file"; rmdir "$scratch"' EXIT

docker() {
  local state
  read -r state < "$state_file"
  case "$1" in
    inspect)
      if [ "$scenario" = inspect_failure ] ||
         { [ "$scenario" = post_stop_inspect_failure ] && [ "$state" = false ]; }; then
        return 1
      fi
      printf '%s\n' "$state"
      ;;
    stop)
      echo stop >> "$calls_file"
      if [ "$scenario" = stop_failure ] ||
         { [ "$scenario" = recovery_stop_failure ] && grep -q '^start$' "$calls_file"; }; then
        return 1
      fi
      if [ "$scenario" != stop_not_effective ]; then
        echo false > "$state_file"
      fi
      ;;
    start)
      echo start >> "$calls_file"
      if [ "$scenario" = start_failure ]; then return 1; fi
      echo true > "$state_file"
      ;;
    *) echo "unexpected docker call: $*" >&2; return 99 ;;
  esac
}

make() {
  [ "$*" = prod-backup ] && [ "$BACKUP_DIR" = ./backups-quiesced ] || return 99
  echo backup >> "$calls_file"
  case "$scenario" in backup_failure*) return 7 ;; esac
}

curl() {
  echo health >> "$calls_file"
  case "$scenario" in health_failure|recovery_stop_failure) return 1 ;; esac
}

sleep() {
  # 실제 60초를 기다리지 않고 health deadline 분기를 시험한다. 런북 자체는 변경하지 않는다.
  SECONDS=$((SECONDS + 61))
}

run_case() {
  scenario=$1
  local initial=$2 expected_rc=$3 expected_backup=$4 expected_recovery=$5 expected_state=$6 expected_calls=$7
  local output rc actual_state actual_calls
  printf '%s\n' "$initial" > "$state_file"
  : > "$calls_file"
  if output=$(eval "$runbook" 2>&1); then rc=0; else rc=$?; fi
  read -r actual_state < "$state_file"
  actual_calls=$(tr '\n' ' ' < "$calls_file")
  if [ "$rc" != "$expected_rc" ] || [ "$actual_state" != "$expected_state" ] ||
     [ "$actual_calls" != "$expected_calls" ] ||
     [[ "$output" != *"BACKUP=$expected_backup BACKUP_EXIT_CODE="* ]] ||
     [[ "$output" != *"APP_RECOVERY=$expected_recovery"* ]]; then
    printf 'FAIL %s: rc=%s state=%s calls=%s\n%s\n' "$scenario" "$rc" "$actual_state" "$actual_calls" "$output" >&2
    exit 1
  fi
  if [[ "$scenario" = backup_failure* ]] && [[ "$output" != *"BACKUP_EXIT_CODE=7"* ]]; then
    echo "FAIL: backup exit code lost" >&2; exit 1
  fi
  printf 'PASS %s: rc=%s backup=%s recovery=%s state=%s\n' "$scenario" "$rc" "$expected_backup" "$expected_recovery" "$actual_state"
}

run_case running_success true 0 SUCCESS SUCCESS true 'stop backup start health '
run_case stopped_success false 0 SUCCESS NOT_REQUIRED_ORIGINALLY_STOPPED false 'backup '
run_case backup_failure_running true 7 FAILED SUCCESS true 'stop backup start health '
run_case backup_failure_stopped false 7 FAILED NOT_REQUIRED_ORIGINALLY_STOPPED false 'backup '
run_case stop_failure true 1 NOT_RUN STOP_FAILED_MANUAL_CHECK_REQUIRED true 'stop '
run_case stop_not_effective true 1 NOT_RUN STOP_UNCONFIRMED_MANUAL_CHECK_REQUIRED true 'stop '
run_case inspect_failure true 1 NOT_RUN NOT_ATTEMPTED true ''
run_case invalid_state unknown 1 NOT_RUN NOT_ATTEMPTED unknown ''
run_case post_stop_inspect_failure true 1 NOT_RUN STOP_UNCONFIRMED_MANUAL_CHECK_REQUIRED false 'stop '
run_case start_failure true 1 SUCCESS FAILED_STOP_CONFIRMED false 'stop backup start stop '
run_case health_failure true 1 SUCCESS FAILED_STOP_CONFIRMED false 'stop backup start health health stop '
run_case recovery_stop_failure true 1 SUCCESS FAILED_STOP_UNCONFIRMED_MANUAL_CHECK_REQUIRED true 'stop backup start health health stop '
