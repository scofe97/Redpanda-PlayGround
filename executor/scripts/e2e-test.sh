#!/bin/bash
set -euo pipefail

# Colors
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'

EXECUTOR_URL="http://localhost:8071"
OPERATOR_URL="http://localhost:8072"
GCP_SSH="gcloud compute ssh dev-server --zone=asia-northeast3-a --project=project-a99c4fa1-6c9e-4491-afd --command"
LOG_PATH="/tmp/executor-logs"

# ---------------------------------------------------------------------------
# Utility functions
# ---------------------------------------------------------------------------

info() { echo -e "${BLUE}[INFO]${NC} $1"; }
pass() { echo -e "${GREEN}[PASS]${NC} $1"; }
fail() { echo -e "${RED}[FAIL]${NC} $1"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }

assert_eq() {
    local actual="$1" expected="$2" msg="$3"
    if [ "$actual" = "$expected" ]; then
        pass "$msg: $actual"
    else
        fail "$msg: expected=$expected, actual=$actual"
    fi
}

assert_contains() {
    local haystack="$1" needle="$2" msg="$3"
    if echo "$haystack" | grep -q "$needle"; then
        pass "$msg"
    else
        fail "$msg: '$needle' not found"
    fi
}

# ---------------------------------------------------------------------------
# Infrastructure / DB helpers
# ---------------------------------------------------------------------------

check_infra() {
    info "Checking infrastructure..."
    local all_ok=true

    curl -sf "${EXECUTOR_URL}/actuator/health" > /dev/null 2>&1 \
        && pass "Executor UP" \
        || { fail "Executor DOWN"; all_ok=false; }

    curl -sf "${OPERATOR_URL}/actuator/health" > /dev/null 2>&1 \
        && pass "Operator-stub UP" \
        || { fail "Operator-stub DOWN"; all_ok=false; }

    nc -z 34.47.83.38 30275 2>/dev/null \
        && pass "PostgreSQL reachable" \
        || { fail "PostgreSQL unreachable"; all_ok=false; }

    nc -z 34.47.83.38 31092 2>/dev/null \
        && pass "Redpanda reachable" \
        || { fail "Redpanda unreachable"; all_ok=false; }

    if [ "$all_ok" = false ]; then
        fail "Infrastructure check failed. Aborting."
        exit 1
    fi
    echo ""
}

clean_db() {
    info "Cleaning DB..."
    $GCP_SSH "kubectl exec -n rp-oss postgresql-0 -- env PGPASSWORD=playground psql -U playground -d playground -c 'DELETE FROM executor.execution_job; DELETE FROM executor.outbox_event; DELETE FROM operator_stub.operator_job;'" > /dev/null 2>&1
    rm -rf ${LOG_PATH}/*
    info "DB and logs cleaned"
}

# ---------------------------------------------------------------------------
# API helpers
# ---------------------------------------------------------------------------

# Returns operatorJobId (which is also the jobExcnId in executor)
trigger_job() {
    local job_name="${1:-executor-test}" jenkins_id="${2:-1}"
    local response
    response=$(curl -s -X POST "${OPERATOR_URL}/api/operator/jobs/execute?jobName=${job_name}&jenkinsInstanceId=${jenkins_id}")
    local op_job_id
    op_job_id=$(echo "$response" | python3 -c "import sys,json; print(json.load(sys.stdin)['operatorJobId'])" 2>/dev/null)
    echo "$op_job_id"
}

# Polls executor job status until it matches expected_status or timeout (seconds)
# Echoes final status; returns 0 on match, 1 on timeout
wait_for_status() {
    local job_excn_id="$1" expected_status="$2" timeout="${3:-90}"
    local elapsed=0
    local status=""
    while [ $elapsed -lt $timeout ]; do
        status=$(curl -s "${EXECUTOR_URL}/api/executor/jobs/${job_excn_id}" \
            | python3 -c "import sys,json; print(json.load(sys.stdin).get('status',''))" 2>/dev/null || echo "")
        if [ "$status" = "$expected_status" ]; then
            echo "$status"
            return 0
        fi
        sleep 3
        elapsed=$((elapsed + 3))
    done
    echo "$status"
    return 1
}

get_executor_job() {
    local job_excn_id="$1"
    curl -s "${EXECUTOR_URL}/api/executor/jobs/${job_excn_id}"
}

get_operator_status() {
    local op_job_id="$1"
    curl -s "${OPERATOR_URL}/api/operator/jobs" | python3 -c "
import sys, json
jobs = json.load(sys.stdin)
for j in jobs:
    if str(j['id']) == '${op_job_id}':
        print(j['status'])
        break
" 2>/dev/null
}

# ---------------------------------------------------------------------------
# TC-01: Happy Path
# ---------------------------------------------------------------------------

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

    local log_file="${LOG_PATH}/executor-test/${job_excn_id}_0"
    if [ -f "$log_file" ]; then
        pass "Log file exists: $log_file"
    else
        fail "Log file not found: $log_file"
    fi

    info "Checking outbox sent=true..."
    local outbox_result
    outbox_result=$($GCP_SSH "kubectl exec -n rp-oss postgresql-0 -- env PGPASSWORD=playground psql -U playground -d playground -t -c \"SELECT COUNT(*) FROM executor.outbox_event WHERE sent=true AND aggregate_id='${job_excn_id}';\"" 2>/dev/null | tr -d ' \n')
    if [ "${outbox_result:-0}" -gt 0 ] 2>/dev/null; then
        pass "Outbox event sent (count=${outbox_result})"
    else
        fail "No sent outbox event found for jobExcnId=${job_excn_id}"
    fi

    echo ""
}

# ---------------------------------------------------------------------------
# TC-02: Build Failure
# ---------------------------------------------------------------------------

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

    local log_file="${LOG_PATH}/executor-test/${job_excn_id}_0"
    if [ -f "$log_file" ]; then
        local log_content
        log_content=$(cat "$log_file")
        if echo "$log_content" | grep -qi "intentional failure\|error"; then
            pass "Log contains failure indicator"
        else
            warn "Log file exists but failure string not found (may be normal if log is from Jenkins)"
        fi
    else
        warn "Log file not found (Jenkins may not have started): $log_file"
    fi

    warn "ACTION REQUIRED: Restore Jenkins pipeline to original script"
    read -p "Press Enter after restoring Jenkins pipeline..."

    echo ""
}

# ---------------------------------------------------------------------------
# TC-03: Trigger Retry (Jenkins scaled down)
# ---------------------------------------------------------------------------

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

# ---------------------------------------------------------------------------
# TC-05: Duplicate Prevention
# ---------------------------------------------------------------------------

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

# ---------------------------------------------------------------------------
# TC-06: Idempotency (re-consume same command)
# ---------------------------------------------------------------------------

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

# ---------------------------------------------------------------------------
# TC-07: Multi-Trigger (3 parallel jobs)
# ---------------------------------------------------------------------------

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

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

main() {
    echo "=================================="
    echo " Executor PoC E2E Test Suite"
    echo "=================================="
    echo ""

    case "${1:-all}" in
        check)  check_infra ;;
        tc01)   check_infra; tc01_happy_path ;;
        tc02)   check_infra; tc02_build_failure ;;
        tc03)   check_infra; tc03_trigger_retry ;;
        tc05)   check_infra; tc05_duplicate_prevention ;;
        tc06)   check_infra; tc06_idempotency ;;
        tc07)   check_infra; tc07_multi_trigger ;;
        auto)
            check_infra
            tc01_happy_path
            tc06_idempotency
            tc07_multi_trigger
            ;;
        all)
            check_infra
            tc01_happy_path
            tc02_build_failure
            tc05_duplicate_prevention
            tc06_idempotency
            tc07_multi_trigger
            tc03_trigger_retry
            ;;
        *)
            echo "Usage: $0 {check|tc01|tc02|tc03|tc05|tc06|tc07|auto|all}"
            echo ""
            echo "  check   Check infrastructure health"
            echo "  tc01    Happy Path (fully automated)"
            echo "  tc02    Build Failure (requires Jenkins pipeline change)"
            echo "  tc03    Trigger Retry / Jenkins scale-down"
            echo "  tc05    Duplicate Prevention (requires Jenkins pipeline change)"
            echo "  tc06    Idempotency (re-consume via offset reset)"
            echo "  tc07    Multi-Trigger (3 parallel jobs)"
            echo "  auto    Automated only: tc01, tc06, tc07"
            echo "  all     All TCs in recommended order"
            ;;
    esac
}

main "$@"
