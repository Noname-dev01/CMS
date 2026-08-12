SHELL := /bin/bash

.PHONY: help dev-db dev-up dev-down logs logs-app logs-db ps rebuild prune prod-up prod-down logs-prod prod-backup

help:
	@echo ""
	@echo "📦 CMS Project Commands"
	@echo "----------------------------"
	@echo "make dev-db      → Start DB only (for IntelliJ development)"
	@echo "make dev-up      → Start full dev stack (app + db)"
	@echo "make dev-down    → Stop dev stack"
	@echo "make logs        → Show all dev logs"
	@echo "make logs-app    → Show app logs"
	@echo "make logs-db     → Show DB logs"
	@echo "make ps          → Show running containers"
	@echo "make rebuild     → Rebuild dev images (no cache)"
	@echo "make prune       → Clean docker build cache"
	@echo "make prod-up     → Start prod stack (app + db, 검증용 — 실배포 아님)"
	@echo "make prod-down   → Stop prod stack (데이터 볼륨 보존)"
	@echo "make logs-prod   → Show prod logs"
	@echo "make prod-backup → Back up prod DB + attachment volume (보존기간 경과분 자동 정리)"
	@echo ""

dev-db:
	@bash scripts/dev-db.sh

dev-up:
	@bash scripts/dev-up.sh

dev-down:
	@bash scripts/dev-down.sh

logs:
	@docker compose -f docker-compose.dev.yml --env-file .env.dev logs -f

logs-app:
	@docker logs -f cms-app-dev

logs-db:
	@docker logs -f cms-db-dev

ps:
	@docker compose -f docker-compose.dev.yml --env-file .env.dev ps

rebuild:
	@docker compose -f docker-compose.dev.yml --env-file .env.dev build --no-cache

prune:
	@docker builder prune -af

prod-up:
	@bash scripts/prod-up.sh

prod-down:
	@bash scripts/prod-down.sh

logs-prod:
	@bash scripts/prod-logs.sh

prod-backup:
	@bash scripts/prod-backup.sh