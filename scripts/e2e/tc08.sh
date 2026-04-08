#!/bin/bash
# TC-08: Mixed Pipeline (Single Pipeline, Multi-Jenkins)

tc08_mixed_pipeline() {
    echo ""
    echo "=== TC-08: Mixed Pipeline (Multi-Jenkins) ==="

    clean_db

    info "Triggering mixed-3-multi-jenkins pipeline..."
    local exec_id
    exec_id=$(trigger_pipeline "mixed-3-multi-jenkins")
    info "executionPipelineId = ${exec_id}"

    info "Waiting for all 3 jobs to complete (timeout=180s)..."
    if wait_for_pipeline_complete "$exec_id" 3 180; then
        pass "All jobs reached terminal state"
    else
        fail "Timeout waiting for pipeline completion"
    fi

    info "Pipeline summary:"
    print_pipeline_summary "$exec_id"

    local success_count
    success_count=$(get_pipeline_jobs "$exec_id" | python3 -c "
import sys, json
jobs = json.load(sys.stdin)
print(sum(1 for j in jobs if j['status'] == 'SUCCESS'))" 2>/dev/null || echo "0")
    assert_eq "$success_count" "3" "SUCCESS job count"

    local jenkins_ids
    jenkins_ids=$(get_pipeline_jobs "$exec_id" | python3 -c "
import sys, json
jobs = json.load(sys.stdin)
ids = sorted(set(j['jenkinsInstanceId'] for j in jobs))
print(','.join(str(i) for i in ids))" 2>/dev/null || echo "")
    assert_contains "$jenkins_ids" "1" "Jenkins-1 used"
    assert_contains "$jenkins_ids" "6" "Jenkins-2 used"

    local log_count
    log_count=$(find "${LOG_PATH}" -name "*_0" -type f 2>/dev/null | wc -l | tr -d ' ')
    if [ "${log_count:-0}" -ge 3 ] 2>/dev/null; then
        pass "Log files exist (count=${log_count})"
    else
        fail "Expected >= 3 log files, found ${log_count}"
    fi

    echo ""
}
