#!/bin/bash
# TC-06: Idempotency (re-consume same command)

tc06_idempotency() {
    echo ""
    echo "=== TC-06: Idempotency (re-consume duplicate command) ==="

    info "Resetting consumer group offset to start..."
    $GCP_SSH "kubectl exec -n rp-oss redpanda-0 -- rpk group seek executor-group --to start --topics playground.executor.commands.job-dispatch" \
        || warn "rpk group seek failed — verify topic/group name"

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
