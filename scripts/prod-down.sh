#!/usr/bin/env bash
set -euo pipefail

# docker compose는 서브커맨드와 무관하게 compose 파일 파싱 시점에 ${VAR:?} 보간을 수행한다 —
# down도 up과 동일하게 호스트 셸의 잔여 환경변수(특히 빈 문자열 export)에 막힐 수 있으므로
# 동일 가드를 적용한다.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./_prod-env-guard.sh
source "$SCRIPT_DIR/_prod-env-guard.sh"
prod_env_guard_unset_host_vars

# -v/--volumes를 절대 쓰지 않는다 — 운영 DB·첨부파일이 named volume에 있다.
# 볼륨을 실제로 비우고 싶다면 운영자가 별도로 명시적인 명령을 직접 실행해야 한다
# (스크립트화하지 않음 — 되돌릴 수 없는 작업을 원클릭으로 만들지 않기 위함,
# PLAN-prod-profile.md 결정 8).
echo "🛑 Stopping prod stack (data volumes preserved)..."
docker compose -f docker-compose.prod.yml --env-file .env.prod down
