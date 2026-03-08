#!/usr/bin/env bash
set -euo pipefail

# Usage: wait-for-service.sh <url> <max_seconds> [service_name]
URL="${1:?Usage: wait-for-service.sh <url> <max_seconds> [service_name]}"
MAX_WAIT="${2:-120}"
SERVICE="${3:-$URL}"

echo "⏳ Waiting for ${SERVICE} (max ${MAX_WAIT}s)..."

elapsed=0
while [ $elapsed -lt "$MAX_WAIT" ]; do
    if curl -sf -o /dev/null --max-time 5 "$URL" 2>/dev/null; then
        echo "✅ ${SERVICE} is ready (${elapsed}s)"
        exit 0
    fi
    sleep 5
    elapsed=$((elapsed + 5))
    echo "   ... ${elapsed}s elapsed"
done

echo "❌ ${SERVICE} not ready after ${MAX_WAIT}s"
exit 1
