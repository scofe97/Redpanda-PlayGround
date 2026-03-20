#!/usr/bin/env bash
# =============================================================
# Redpanda 토픽 모니터링 스크립트
# Job/Pipeline E2E 테스트 시 이벤트 흐름 실시간 관찰용
#
# 사용법:
#   sh http/monitor-redpanda.sh [command]
#
# Commands:
#   list      토픽 목록 확인
#   commands  실행 커맨드 토픽 모니터링
#   steps     Job 상태 변경 이벤트 모니터링
#   completed 파이프라인 완료 이벤트 모니터링
#   jenkins   Jenkins 커맨드 토픽 모니터링
#   tickets   티켓 이벤트 모니터링
#   webhook   웹훅 인바운드 모니터링
#   dlq       Dead Letter Queue 모니터링
#   all       모든 주요 토픽 동시 모니터링 (tmux 필요)
#   audit     감사 이벤트 모니터링
# =============================================================

set -euo pipefail

BROKER="${REDPANDA_BROKER:-34.47.83.38:29092}"

# 토픽 이름
TOPIC_CMD_EXECUTION="playground.pipeline.commands.execution"
TOPIC_CMD_JENKINS="playground.pipeline.commands.jenkins"
TOPIC_EVT_STEP_CHANGED="playground.pipeline.events.step-changed"
TOPIC_EVT_COMPLETED="playground.pipeline.events.completed"
TOPIC_TICKET_EVENTS="playground.ticket.events"
TOPIC_WEBHOOK_INBOUND="playground.webhook.inbound"
TOPIC_AUDIT_EVENTS="playground.audit.events"
TOPIC_DLQ="playground.dlq"

consume() {
  local topic="$1"
  local label="$2"
  echo "=== [$label] Consuming: $topic ==="
  echo "    Broker: $BROKER"
  echo "    Ctrl+C to stop"
  echo ""
  rpk topic consume "$topic" \
    --brokers "$BROKER" \
    --format json \
    --offset end
}

consume_from_start() {
  local topic="$1"
  local label="$2"
  echo "=== [$label] Consuming (from start): $topic ==="
  rpk topic consume "$topic" \
    --brokers "$BROKER" \
    --format json \
    --offset start
}

case "${1:-help}" in
  list)
    echo "=== Redpanda 토픽 목록 ==="
    rpk topic list --brokers "$BROKER"
    ;;

  commands)
    consume "$TOPIC_CMD_EXECUTION" "Execution Commands"
    ;;

  steps)
    consume "$TOPIC_EVT_STEP_CHANGED" "Step Changed Events"
    ;;

  completed)
    consume "$TOPIC_EVT_COMPLETED" "Completed Events"
    ;;

  jenkins)
    consume "$TOPIC_CMD_JENKINS" "Jenkins Commands"
    ;;

  tickets)
    consume "$TOPIC_TICKET_EVENTS" "Ticket Events"
    ;;

  webhook)
    consume "$TOPIC_WEBHOOK_INBOUND" "Webhook Inbound"
    ;;

  dlq)
    consume "$TOPIC_DLQ" "Dead Letter Queue"
    ;;

  audit)
    consume "$TOPIC_AUDIT_EVENTS" "Audit Events"
    ;;

  all)
    if ! command -v tmux &>/dev/null; then
      echo "Error: tmux가 필요합니다. 'brew install tmux'로 설치하세요."
      echo ""
      echo "대안: 별도 터미널에서 개별 명령을 실행하세요:"
      echo "  sh http/monitor-redpanda.sh commands"
      echo "  sh http/monitor-redpanda.sh steps"
      echo "  sh http/monitor-redpanda.sh completed"
      exit 1
    fi

    SESSION="redpanda-monitor"
    tmux kill-session -t "$SESSION" 2>/dev/null || true
    tmux new-session -d -s "$SESSION" -n "commands" \
      "sh $0 commands; read"
    tmux split-window -t "$SESSION" -v \
      "sh $0 steps; read"
    tmux split-window -t "$SESSION" -h \
      "sh $0 completed; read"
    tmux select-layout -t "$SESSION" tiled
    tmux attach-session -t "$SESSION"
    ;;

  history)
    echo "=== 모든 토픽 메시지 (처음부터) ==="
    echo ""
    for topic in "$TOPIC_CMD_EXECUTION" "$TOPIC_EVT_STEP_CHANGED" "$TOPIC_EVT_COMPLETED"; do
      echo "--- $topic ---"
      rpk topic consume "$topic" \
        --brokers "$BROKER" \
        --format json \
        --offset start \
        --num 10 2>/dev/null || echo "(비어 있음)"
      echo ""
    done
    ;;

  help|*)
    echo "Redpanda 토픽 모니터링 스크립트"
    echo ""
    echo "사용법: sh http/monitor-redpanda.sh [command]"
    echo ""
    echo "Commands:"
    echo "  list       토픽 목록 확인"
    echo "  commands   실행 커맨드 토픽 (Outbox → Kafka)"
    echo "  steps      Job 상태 변경 이벤트"
    echo "  completed  파이프라인 완료 이벤트"
    echo "  jenkins    Jenkins 커맨드 토픽"
    echo "  tickets    티켓 이벤트"
    echo "  webhook    웹훅 인바운드"
    echo "  dlq        Dead Letter Queue"
    echo "  audit      감사 이벤트"
    echo "  all        주요 토픽 동시 모니터링 (tmux 필요)"
    echo "  history    모든 주요 토픽 최근 10건 조회"
    echo ""
    echo "검증 체크리스트:"
    echo "  1. Job 10개 생성       → GET /api/jobs → 10건"
    echo "  2. Pipeline 3개 생성   → GET /api/pipelines → 3건"
    echo "  3. Job 단독 실행       → POST /api/jobs/{id}/execute → 202"
    echo "  4. Outbox 이벤트 발행  → DB: SELECT * FROM outbox_event"
    echo "  5. 커맨드 토픽 수신    → sh $0 commands"
    echo "  6. Step 상태 변경      → sh $0 steps"
    echo "  7. 완료 이벤트         → sh $0 completed"
    echo "  8. DAG Fan-out 확인    → 서버 로그: [DAG] 키워드"
    echo ""
    echo "Redpanda Console: http://localhost:28080"
    ;;
esac
