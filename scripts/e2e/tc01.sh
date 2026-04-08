#!/bin/bash
# TC-01: Happy Path (단독 Job)

tc01_happy_path() {
    echo ""
    echo "=== TC-01: Happy Path ==="

    clean_db

    info "Triggering job..."
    local job_excn_id
    job_excn_id=$(trigger_job "executor-test" "1")
    info "jobExcnId = ${job_excn_id}"

    info "Waiting for SUCCESS (timeout=90s)..."
    local final_status
    final_status=$(wait_for_status "$job_excn_id" "SUCCESS" 90) || true

    assert_eq "$final_status" "SUCCESS" "Executor job status"

    local op_status
    op_status=$(get_operator_status "$job_excn_id")
    assert_eq "$op_status" "SUCCESS" "Operator job status"

    local log_file
    log_file=$(find "${LOG_PATH}" -name "${job_excn_id}_0" -type f 2>/dev/null | head -1)
    if [ -n "$log_file" ]; then
        pass "Log file exists: $log_file"
    else
        fail "Log file not found: ${LOG_PATH}/**/${job_excn_id}_0"
    fi

    info "Checking outbox status=SENT..."
    local outbox_result
    outbox_result=$($GCP_SSH "kubectl exec -n rp-oss postgresql-0 -- env PGPASSWORD=playground psql -U playground -d playground -t -c \"SELECT COUNT(*) FROM executor.outbox_event WHERE status='SENT' AND aggregate_id='${job_excn_id}';\"" 2>/dev/null | tr -d ' \n')
    if [ "${outbox_result:-0}" -gt 0 ] 2>/dev/null; then
        pass "Outbox event sent (count=${outbox_result})"
    else
        fail "No sent outbox event found for jobExcnId=${job_excn_id}"
    fi

    echo ""
}
