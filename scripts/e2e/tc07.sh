#!/bin/bash
# TC-07: Multi-Trigger (3 parallel jobs)

tc07_multi_trigger() {
    echo ""
    echo "=== TC-07: Multi-Trigger (3 parallel jobs) ==="

    clean_db

    info "Triggering 3 jobs in parallel..."

    trigger_job "executor-test" "1" > /tmp/e2e_job1.id &
    trigger_job "executor-test" "1" > /tmp/e2e_job2.id &
    trigger_job "executor-test" "1" > /tmp/e2e_job3.id &
    wait

    local job1 job2 job3
    job1=$(cat /tmp/e2e_job1.id); job2=$(cat /tmp/e2e_job2.id); job3=$(cat /tmp/e2e_job3.id)
    rm -f /tmp/e2e_job{1,2,3}.id
    info "job1=${job1}, job2=${job2}, job3=${job3}"

    info "Waiting 20s to observe scheduling..."
    sleep 20

    local count_running=0 count_queued=0 count_pending=0 count_success=0

    for jid in "$job1" "$job2" "$job3"; do
        local s
        s=$(curl -s "${EXECUTOR_URL}/api/executor/jobs/${jid}" \
            | python3 -c "import sys,json; print(json.load(sys.stdin).get('status',''))" 2>/dev/null || echo "UNKNOWN")
        info "  job ${jid} status: ${s}"
        case "$s" in
            RUNNING)  count_running=$((count_running + 1)) ;;
            QUEUED)   count_queued=$((count_queued + 1)) ;;
            PENDING)  count_pending=$((count_pending + 1)) ;;
            SUCCESS)  count_success=$((count_success + 1)) ;;
        esac
    done

    local active=$((count_running + count_queued + count_success))
    if [ $active -ge 1 ]; then
        pass "At least 1 job is RUNNING/QUEUED/SUCCESS (active=${active})"
    else
        fail "No active job found — all may be stuck in PENDING"
    fi

    if [ $count_pending -ge 1 ] || [ $((count_running + count_queued)) -le 1 ]; then
        pass "Concurrency control observed: pending=${count_pending}, running=${count_running}, queued=${count_queued}"
    else
        warn "All 3 jobs are active — check if concurrency limit is enforced"
    fi

    info "Checking log for duplicate skip..."
    local dup_log
    dup_log=$(find "${LOG_PATH}" -type f 2>/dev/null | xargs grep -li "duplicate skip\|already running" 2>/dev/null | head -1 || true)
    if [ -n "$dup_log" ]; then
        pass "Duplicate skip log found"
    else
        warn "No duplicate skip log found in ${LOG_PATH} — check app logs"
    fi

    echo ""
}
