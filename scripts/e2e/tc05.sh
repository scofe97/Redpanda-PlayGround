#!/bin/bash
# TC-05: Duplicate Prevention

tc05_duplicate_prevention() {
    echo ""
    echo "=== TC-05: Duplicate Prevention ==="

    warn "ACTION REQUIRED: Change Jenkins pipeline to 'sleep 120' (or equivalent long-running step)"
    read -p "Press Enter after changing Jenkins pipeline..."

    clean_db

    info "Triggering first job (jobA)..."
    local job_a
    job_a=$(trigger_job "executor-test" "1")
    info "jobA = ${job_a}"

    info "Waiting 15s for jobA to reach RUNNING/QUEUED..."
    sleep 15

    info "Triggering second job (jobB)..."
    local job_b
    job_b=$(trigger_job "executor-test" "1")
    info "jobB = ${job_b}"

    sleep 10

    local status_a
    status_a=$(curl -s "${EXECUTOR_URL}/api/executor/jobs/${job_a}" \
        | python3 -c "import sys,json; print(json.load(sys.stdin).get('status',''))" 2>/dev/null || echo "")

    if [ "$status_a" = "RUNNING" ] || [ "$status_a" = "QUEUED" ]; then
        pass "jobA status is ${status_a} (Jenkins busy)"
    else
        warn "jobA status: ${status_a} (may have completed quickly)"
    fi

    info "Checking executor log for duplicate skip..."
    local dup_log
    dup_log=$(find "${LOG_PATH}" -type f 2>/dev/null | xargs grep -li "duplicate skip\|already running\|duplicate" 2>/dev/null | head -1 || true)
    if [ -n "$dup_log" ]; then
        pass "Duplicate skip log found: $dup_log"
    else
        warn "No duplicate skip log found in ${LOG_PATH} (check application logs directly)"
    fi

    warn "ACTION REQUIRED: Restore Jenkins pipeline to original script"
    read -p "Press Enter after restoring Jenkins pipeline..."

    echo ""
}
