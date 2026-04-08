#!/bin/bash
# TC-03: Trigger Retry (Jenkins scaled down)

tc03_trigger_retry() {
    echo ""
    echo "=== TC-03: Trigger Retry (Jenkins scale-down) ==="

    warn "This test will scale down Jenkins to 0 replicas!"
    read -p "Continue? (y/N) " confirm
    if [[ "${confirm,,}" != "y" ]]; then
        warn "Skipping TC-03."
        return 0
    fi

    info "Scaling down Jenkins..."
    $GCP_SSH "kubectl scale statefulset jenkins -n rp-jenkins --replicas=0"
    sleep 10

    clean_db

    info "Triggering job while Jenkins is down..."
    local job_excn_id
    job_excn_id=$(trigger_job "executor-test" "1")
    info "jobExcnId = ${job_excn_id}"

    info "Waiting 20s for trigger attempt + retry..."
    sleep 20

    local job_json
    job_json=$(get_executor_job "$job_excn_id")

    local retry_cnt
    retry_cnt=$(echo "$job_json" | python3 -c "import sys,json; print(json.load(sys.stdin).get('retryCnt', 0))" 2>/dev/null || echo "0")

    local current_status
    current_status=$(echo "$job_json" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status',''))" 2>/dev/null || echo "")

    if [ "${retry_cnt:-0}" -ge 1 ] 2>/dev/null; then
        pass "retryCnt >= 1: actual=${retry_cnt}"
    else
        fail "retryCnt expected >= 1, got: ${retry_cnt}"
    fi

    assert_eq "$current_status" "PENDING" "Executor job status while Jenkins down"

    info "Restoring Jenkins (scale up)..."
    $GCP_SSH "kubectl scale statefulset jenkins -n rp-jenkins --replicas=1"
    info "Waiting 60s for Jenkins to be ready..."
    sleep 60

    echo ""
}
