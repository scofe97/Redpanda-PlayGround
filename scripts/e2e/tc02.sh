#!/bin/bash
# TC-02: Build Failure

tc02_build_failure() {
    echo ""
    echo "=== TC-02: Build Failure ==="

    warn "ACTION REQUIRED: Change Jenkins pipeline script to include 'error(\"Intentional failure\")'"
    read -p "Press Enter after changing Jenkins pipeline..."

    clean_db

    info "Triggering job..."
    local job_excn_id
    job_excn_id=$(trigger_job "executor-test" "1")
    info "jobExcnId = ${job_excn_id}"

    info "Waiting for FAILURE (timeout=90s)..."
    local final_status
    final_status=$(wait_for_status "$job_excn_id" "FAILURE" 90) || true

    assert_eq "$final_status" "FAILURE" "Executor job status"

    local op_status
    op_status=$(get_operator_status "$job_excn_id")
    assert_eq "$op_status" "FAILURE" "Operator job status"

    local log_file
    log_file=$(find "${LOG_PATH}" -name "${job_excn_id}_0" -type f 2>/dev/null | head -1)
    if [ -n "$log_file" ]; then
        local log_content
        log_content=$(cat "$log_file")
        if echo "$log_content" | grep -qi "intentional failure\|error"; then
            pass "Log contains failure indicator"
        else
            warn "Log file exists but failure string not found (may be normal if log is from Jenkins)"
        fi
    else
        warn "Log file not found (Jenkins may not have started): ${LOG_PATH}/**/${job_excn_id}_0"
    fi

    warn "ACTION REQUIRED: Restore Jenkins pipeline to original script"
    read -p "Press Enter after restoring Jenkins pipeline..."

    echo ""
}
