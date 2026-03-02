#!/usr/bin/env bash
set -euo pipefail

echo "🛑 Stopping production-like stack..."
docker compose -f docker-compose.prod.yml --env-file .env.prod down
echo "✅ Prod-like stack stopped."