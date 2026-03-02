#!/usr/bin/env bash
set -euo pipefail

echo "🚀 Starting production-like stack (nginx + app + db)..."
docker compose -f docker-compose.prod.yml --env-file .env.prod up --build -d
echo "✅ Running at http://localhost"