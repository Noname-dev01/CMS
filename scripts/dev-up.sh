#!/usr/bin/env bash
set -euo pipefail

echo "🚀 Starting full dev stack (app + db)..."
docker compose -f docker-compose.dev.yml --env-file .env.dev up --build