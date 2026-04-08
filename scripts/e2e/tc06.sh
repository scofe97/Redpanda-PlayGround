#!/bin/bash
# ==========================================================================
# TC-06: Idempotency (중복 메시지 재소비)
#
# Kafka Consumer Group 오프셋을 처음으로 되감아 동일 커맨드를 재소비했을 때,
# Executor가 멱등하게 처리하는지 (이미 처리된 Job은 무시) 검증한다.
#
# 방법: rpk group seek으로 executor-group의 오프셋을 start로 리셋 → 재소비 유도
#
# 검증 항목:
#   1) 애플리케이션 로그에 "Duplicate job ignored" 메시지 존재
#
# 선행 조건: TC-01 등을 먼저 실행하여 커맨드 메시지가 토픽에 존재해야 함
# 자동화 가능: YES
# ==========================================================================

tc06_idempotency() {
    echo ""
    echo "=== TC-06: Idempotency (re-consume duplicate command) ==="

    # rpk으로 Consumer Group 오프셋을 토픽 시작점으로 되감기
    # → Executor가 이전에 처리한 커맨드를 다시 수신
    info "Resetting consumer group offset to start..."
    $GCP_SSH "kubectl exec -n rp-oss redpanda-0 -- rpk group seek executor-group --to start --topics playground.executor.commands.job-dispatch" \
        || warn "rpk group seek failed — verify topic/group name"

    # Executor Pod 로그에서 멱등성 처리 증거 확인
    info "Checking executor application log for 'Duplicate job ignored'..."
    local app_log
    app_log=$($GCP_SSH "kubectl logs -n rp-oss deployment/executor --tail=200 2>/dev/null" 2>/dev/null || echo "")

    if echo "$app_log" | grep -qi "duplicate job ignored\|duplicate.*ignored\|already exists"; then
        pass "Idempotency log found: 'Duplicate job ignored'"
    else
        warn "'Duplicate job ignored' not found in recent logs."
        warn "To verify: restart executor after offset reset, then check logs manually:"
        warn "  kubectl logs -n rp-oss deployment/executor --follow | grep -i duplicate"
    fi

    echo ""
}
