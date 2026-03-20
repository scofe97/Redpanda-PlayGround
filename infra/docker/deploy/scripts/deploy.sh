#!/usr/bin/env bash
# ============================================================================
# GCP 멀티 서버 배포 스크립트
# ============================================================================
# 순서: 방화벽 → Server 3 (모니터링) → Server 1 (데이터) → Server 2 (sidecar) → Local
#
# 사용법:
#   ./deploy.sh           # 전체 배포
#   ./deploy.sh server-3  # 특정 서버만 배포
#   ./deploy.sh firewall  # 방화벽만 생성
# ============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DEPLOY_DIR="$(dirname "$SCRIPT_DIR")"

# GCP VM 이름
VM1="dev-server"
VM2="dev-server-2"
VM3="dev-server-3"

REMOTE_DIR="~/deploy"

log() { echo "[$(date '+%H:%M:%S')] $*"; }

# --- 방화벽 규칙 ---
deploy_firewall() {
  log "방화벽 규칙 생성..."

  # Server 1: Redpanda + DB 접근용
  gcloud compute firewall-rules create playground-server1 \
    --allow tcp:29092,tcp:28081,tcp:28080,tcp:25432,tcp:29644 \
    --target-tags http-server \
    --description "Redpanda Playground - Server 1 (Redpanda, DB, Console)" \
    2>/dev/null || log "playground-server1 규칙이 이미 존재합니다"

  # Server 3: Grafana + OTLP + Prometheus 접근용
  gcloud compute firewall-rules create playground-server3 \
    --allow tcp:23000,tcp:4317,tcp:4318,tcp:9090,tcp:3100 \
    --target-tags http-server \
    --description "Redpanda Playground - Server 3 (Monitoring)" \
    2>/dev/null || log "playground-server3 규칙이 이미 존재합니다"

  log "방화벽 규칙 완료"
}

# --- 서버 배포 함수 ---
deploy_server() {
  local vm=$1
  local dir=$2
  log "[$vm] 배포 시작..."

  # 파일 전송
  gcloud compute scp --recurse "${DEPLOY_DIR}/${dir}/" "${vm}:${REMOTE_DIR}/" --quiet

  # Docker Compose 실행
  gcloud compute ssh "$vm" --command \
    "cd ${REMOTE_DIR} && docker compose pull --quiet && docker compose up -d"

  log "[$vm] 배포 완료"
}

# --- Server 2 (Alloy sidecar만 추가) ---
deploy_server2() {
  log "[$VM2] Alloy sidecar 배포 시작..."

  # 사전 확인: 기존 네트워크/compose 현황
  log "[$VM2] 기존 Docker 환경 확인..."
  gcloud compute ssh "$VM2" --command \
    "docker network ls && echo '---' && docker compose ls" || true

  # Alloy sidecar 파일 전송 및 실행
  gcloud compute scp --recurse "${DEPLOY_DIR}/server-2/" "${VM2}:${REMOTE_DIR}/" --quiet
  gcloud compute ssh "$VM2" --command \
    "cd ${REMOTE_DIR} && docker compose up -d"

  log "[$VM2] Alloy sidecar 배포 완료"
}

# --- 로컬 Alloy ---
deploy_local() {
  log "[local] Alloy sidecar 시작..."
  cd "${DEPLOY_DIR}/local"
  docker compose up -d
  log "[local] Alloy sidecar 시작 완료"
  log "[local] Spring Boot 시작: SPRING_PROFILES_ACTIVE=gcp make backend"
}

# --- 메인 ---
TARGET="${1:-all}"

case "$TARGET" in
  firewall)
    deploy_firewall
    ;;
  server-3)
    deploy_server "$VM3" "server-3"
    ;;
  server-1)
    deploy_server "$VM1" "server-1"
    ;;
  server-2)
    deploy_server2
    ;;
  local)
    deploy_local
    ;;
  all)
    deploy_firewall
    deploy_server "$VM3" "server-3"   # 모니터링 먼저
    deploy_server "$VM1" "server-1"   # 데이터 플레인
    deploy_server2                     # Alloy sidecar
    deploy_local                       # 로컬
    log "전체 배포 완료. ./verify.sh로 헬스체크하세요."
    ;;
  *)
    echo "Usage: $0 {all|firewall|server-1|server-2|server-3|local}"
    exit 1
    ;;
esac
