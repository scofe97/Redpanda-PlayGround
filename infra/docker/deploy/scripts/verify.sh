#!/usr/bin/env bash
# ============================================================================
# GCP 배포 헬스체크 스크립트
# ============================================================================
# 각 서버의 주요 서비스 상태를 확인한다.
#
# 사용법: ./verify.sh
# ============================================================================

set -euo pipefail

SERVER1_EXT="34.47.83.38"
SERVER3_EXT="34.22.78.240"

GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m'

check() {
  local name=$1
  local url=$2
  local timeout=${3:-5}

  if curl -sf --max-time "$timeout" "$url" > /dev/null 2>&1; then
    echo -e "  ${GREEN}✓${NC} $name"
    return 0
  else
    echo -e "  ${RED}✗${NC} $name ($url)"
    return 1
  fi
}

check_pg() {
  if PGPASSWORD=playground psql -h "$SERVER1_EXT" -p 25432 -U playground -c "SELECT 1" > /dev/null 2>&1; then
    echo -e "  ${GREEN}✓${NC} PostgreSQL"
    return 0
  else
    echo -e "  ${RED}✗${NC} PostgreSQL ($SERVER1_EXT:25432)"
    return 1
  fi
}

FAILED=0

echo "=== Server 1 ($SERVER1_EXT) — Redpanda + DB ==="
check "Redpanda Admin"        "http://${SERVER1_EXT}:29644/v1/node/config"    || ((FAILED++))
check "Schema Registry"       "http://${SERVER1_EXT}:28081/subjects"          || ((FAILED++))
check "Console"               "http://${SERVER1_EXT}:28080"                   || ((FAILED++))
check_pg                                                                       || ((FAILED++))

echo ""
echo "=== Server 3 ($SERVER3_EXT) — Monitoring ==="
check "Grafana"               "http://${SERVER3_EXT}:23000/api/health"        || ((FAILED++))
check "Loki"                  "http://${SERVER3_EXT}:3100/ready"              || ((FAILED++))
check "Prometheus"            "http://${SERVER3_EXT}:9090/-/ready"            || ((FAILED++))

echo ""
echo "=== Server 2 — Alloy Sidecar ==="
echo "  (Docker socket 기반 — 외부 헬스체크 불가, SSH로 확인)"
echo "  gcloud compute ssh dev-server-2 -- 'docker ps --format \"{{.Names}} {{.Status}}\" | grep alloy'"

echo ""
echo "=== Local — Alloy Sidecar ==="
check "Local Alloy UI"        "http://localhost:24312"                         || ((FAILED++))

echo ""
if [ "$FAILED" -eq 0 ]; then
  echo -e "${GREEN}모든 헬스체크 통과${NC}"
else
  echo -e "${RED}${FAILED}개 서비스 헬스체크 실패${NC}"
  exit 1
fi

echo ""
echo "=== E2E 검증 ==="
echo "1. Grafana 접속: http://${SERVER3_EXT}:23000"
echo "2. Explore > Loki: {container=~\"playground-.*\"}"
echo "3. 3개 서버 로그가 모두 표시되는지 확인"
echo "4. Spring Boot 요청 후 Tempo에서 트레이스 확인"
