#!/usr/bin/env bash
set -euo pipefail

echo "🛑 Stopping dev stack..."
docker compose -f docker-compose.dev.yml --env-file .env.dev down
echo "✅ Dev stack stopped."