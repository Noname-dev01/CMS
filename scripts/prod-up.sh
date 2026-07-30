#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./_prod-env-guard.sh
source "$SCRIPT_DIR/_prod-env-guard.sh"
prod_env_guard_unset_host_vars

echo "🚀 Starting prod stack (app + db)..."
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d --build

# 기존 컨테이너를 재사용하는 재실행이면 RestartCount에 과거 재시작 이력이 이미 누적돼
# 있을 수 있다 — 이번 기동에서 새로 발생한 재시작만 판정하도록 기준값을 먼저 저장한다.
baseline_restart_count=$(docker inspect --format='{{.RestartCount}}' cms-app-prod 2>/dev/null || echo "0")

echo "⏳ Waiting for /actuator/health (최대 60초)..."
# 벽시계 기준 데드라인 — 반복 횟수(간격×횟수) 방식은 curl 자체의 소요 시간이 누적돼
# 실제 총 대기 시간이 예산을 넘길 수 있다(PLAN-prod-profile.md 결정 8, v6·v7).
deadline=$((SECONDS + 60))
while true; do
  remaining=$((deadline - SECONDS))
  if (( remaining <= 0 )); then
    echo "❌ 60초 내에 /actuator/health가 응답하지 않았습니다."
    docker compose -f docker-compose.prod.yml --env-file .env.prod logs app || true
    # restart: unless-stopped로 인한 재시작 루프를 멈춘다 — down이 아니라 stop이므로
    # 컨테이너·로그·볼륨은 그대로 남아 사후 분석이 가능하다.
    docker compose -f docker-compose.prod.yml --env-file .env.prod stop app || true
    exit 1
  fi

  timeout=$(( remaining < 4 ? remaining : 4 ))
  if curl --fail --silent --show-error --connect-timeout 2 --max-time "$timeout" \
       http://127.0.0.1:8080/actuator/health > /dev/null; then
    break
  fi

  remaining=$((deadline - SECONDS))
  if (( remaining <= 0 )); then
    echo "❌ 60초 내에 /actuator/health가 응답하지 않았습니다."
    docker compose -f docker-compose.prod.yml --env-file .env.prod logs app || true
    docker compose -f docker-compose.prod.yml --env-file .env.prod stop app || true
    exit 1
  fi
  sleep "$(( remaining < 5 ? remaining : 5 ))"
done

# Tomcat은 컨텍스트 refresh 단계에서 이미 리스닝을 시작하며, AdminBootstrapLoader 같은
# CommandLineRunner는 그 이후(callRunners)에 실행된다 — 즉 health가 200을 반환한 뒤에도
# 러너가 필수 환경변수 누락 등으로 예외를 던져 기동이 최종 실패할 수 있는 창이 존재한다.
# restart: unless-stopped 재시작 여부로 그 실패를 사후에 감지한다.
echo "🔎 러너 완료 후 안정 상태인지 재확인 중 (3초 대기 후 재검사)..."
sleep 3
current_restart_count=$(docker inspect --format='{{.RestartCount}}' cms-app-prod 2>/dev/null || echo "?")
if [ "$current_restart_count" != "$baseline_restart_count" ]; then
  echo "❌ health 응답 이후 컨테이너가 재시작됐습니다(RestartCount: $baseline_restart_count → $current_restart_count) — CommandLineRunner(AdminBootstrapLoader 등) 초기화가 health 성공 이후 실패했을 수 있습니다."
  docker compose -f docker-compose.prod.yml --env-file .env.prod logs app || true
  docker compose -f docker-compose.prod.yml --env-file .env.prod stop app || true
  exit 1
fi
if ! curl --fail --silent --show-error --connect-timeout 2 --max-time 4 \
     http://127.0.0.1:8080/actuator/health > /dev/null; then
  echo "❌ 안정성 재확인에서 /actuator/health가 실패했습니다 — 초기 200 응답이 러너 실패 직전의 일시적 상태였을 수 있습니다."
  docker compose -f docker-compose.prod.yml --env-file .env.prod logs app || true
  docker compose -f docker-compose.prod.yml --env-file .env.prod stop app || true
  exit 1
fi

echo "✅ prod 스택이 정상 기동했습니다 (http://127.0.0.1:8080)."
echo "⚠️  이 포트 바인딩은 검증용입니다 — 리버스 프록시·TLS 없이 그대로 인터넷에 노출하지 마세요."
