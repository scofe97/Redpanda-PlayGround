#!/usr/bin/env bash
set -euo pipefail

API_URL="${API_URL:-http://localhost:8080}"

echo "=== Demo: Multi-Source Deploy ==="
echo ""

# 1. Create ticket with multiple sources (GIT x2 + NEXUS x1 + HARBOR x1)
echo "📝 Creating deployment ticket with 4 sources..."

TICKET_RESPONSE=$(curl -sf -X POST "$API_URL/api/tickets" \
    -H "Content-Type: application/json" \
    -d '{
        "name": "[DEMO] Multi-Source Deployment",
        "description": "GitLab 2개 + Nexus 1개 + Harbor 1개 복합 소스 배포 데모",
        "sources": [
            {
                "sourceType": "GIT",
                "repoUrl": "http://localhost:29180/root/egov-sample.git",
                "branch": "main"
            },
            {
                "sourceType": "GIT",
                "repoUrl": "http://localhost:29180/root/portal-app.git",
                "branch": "develop"
            },
            {
                "sourceType": "NEXUS",
                "artifactCoordinate": "com.example:egov-sample:1.0.0:war"
            },
            {
                "sourceType": "HARBOR",
                "imageName": "localhost:25050/egov-sample:1.0.0"
            }
        ]
    }')

TICKET_ID=$(echo "$TICKET_RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])" 2>/dev/null)

if [ -z "$TICKET_ID" ]; then
    echo "❌ Failed to create ticket"
    echo "  Response: $TICKET_RESPONSE"
    exit 1
fi
echo "  ✅ Ticket #$TICKET_ID created"

# 2. Start pipeline
echo ""
echo "🚀 Starting pipeline for ticket #$TICKET_ID..."
PIPELINE_RESPONSE=$(curl -sf -X POST "$API_URL/api/tickets/$TICKET_ID/pipeline/start" \
    -H "Content-Type: application/json")
echo "  ✅ Pipeline started"
echo "  $PIPELINE_RESPONSE" | python3 -c "import sys,json; d=json.load(sys.stdin).get('data',{}); print(f\"  Execution ID: {d.get('id','N/A')}\")" 2>/dev/null || true

# 3. Stream SSE events (timeout after 60s)
echo ""
echo "📡 Streaming pipeline events (60s timeout)..."
echo "---"
timeout 60 curl -sN "$API_URL/api/tickets/$TICKET_ID/pipeline/events" 2>/dev/null | while IFS= read -r line; do
    if [[ "$line" == data:* ]]; then
        echo "  $line"
    fi
done || true
echo "---"

# 4. Final status
echo ""
echo "📊 Final pipeline status:"
curl -sf "$API_URL/api/tickets/$TICKET_ID/pipeline" \
    -H "Accept: application/json" | python3 -m json.tool 2>/dev/null || echo "  (Backend may not be running)"

echo ""
echo "=== Demo Complete ==="
echo "  Ticket: $API_URL/api/tickets/$TICKET_ID"
echo "  Frontend: http://localhost:5173"
