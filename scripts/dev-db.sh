#!/usr/bin/env bash
set -euo pipefail

echo "🚀 Starting development database (db only)..."
docker compose -f docker-compose.dev.yml --env-file .env.dev up -d db
echo "✅ Development DB is running on localhost:3307"