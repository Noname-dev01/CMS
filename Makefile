SHELL := /bin/bash

.PHONY: help dev-db dev-up dev-down prod-up prod-down logs logs-app logs-db ps rebuild prune

help:
	@echo ""
	@echo "📦 CMS Project Commands"
	@echo "----------------------------"
	@echo "make dev-db      → Start DB only (for IntelliJ development)"
	@echo "make dev-up      → Start full dev stack (app + db)"
	@echo "make dev-down    → Stop dev stack"
	@echo "make prod-up     → Start production-like stack (nginx + app + db)"
	@echo "make prod-down   → Stop production-like stack"
	@echo "make logs        → Show all dev logs"
	@echo "make logs-app    → Show app logs"
	@echo "make logs-db     → Show DB logs"
	@echo "make ps          → Show running containers"
	@echo "make rebuild     → Rebuild dev images (no cache)"
	@echo "make prune       → Clean docker build cache"
	@echo ""

dev-db:
	@bash scripts/dev-db.sh

dev-up:
	@bash scripts/dev-up.sh

dev-down:
	@bash scripts/dev-down.sh

prod-up:
	@bash scripts/prod-up.sh

prod-down:
	@bash scripts/prod-down.sh

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