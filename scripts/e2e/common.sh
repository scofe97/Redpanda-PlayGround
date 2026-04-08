#!/bin/bash
# E2E 공통 환경변수, 유틸리티, 헬퍼 함수

# Colors
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'

EXECUTOR_URL="http://localhost:8071"
OPERATOR_URL="http://localhost:8072"
GCP_SSH="gcloud compute ssh dev-server --zone=asia-northeast3-a --project=project-a99c4fa1-6c9e-4491-afd --command"
LOG_PATH="/tmp/executor-logs"

# ---------------------------------------------------------------------------
# Output helpers
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
# Infrastructure
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
# Single Job helpers
# ---------------------------------------------------------------------------

trigger_job() {
    local job_name="${1:-executor-test}" jenkins_id="${2:-1}"
    local response
    response=$(curl -s -X POST "${OPERATOR_URL}/api/operator/jobs/execute?jobName=${job_name}&jenkinsInstanceId=${jenkins_id}")
    echo "$response" | python3 -c "import sys,json; print(json.load(sys.stdin)['operatorJobId'])" 2>/dev/null
}

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
# Pipeline helpers
# ---------------------------------------------------------------------------

trigger_pipeline() {
    local pipeline_id="$1"
    local response
    response=$(curl -s -X POST "${OPERATOR_URL}/api/operator/pipelines/${pipeline_id}/execute")
    echo "$response" | python3 -c "import sys,json; print(json.load(sys.stdin)['executionPipelineId'])" 2>/dev/null
}

get_pipeline_jobs() {
    local exec_pipeline_id="$1"
    curl -s "${OPERATOR_URL}/api/operator/jobs?executionPipelineId=${exec_pipeline_id}"
}

wait_for_pipeline_complete() {
    local exec_pipeline_id="$1" expected_count="$2" timeout="${3:-180}"
    local elapsed=0
    while [ $elapsed -lt $timeout ]; do
        local terminal_count
        terminal_count=$(get_pipeline_jobs "$exec_pipeline_id" | python3 -c "
import sys, json
jobs = json.load(sys.stdin)
print(sum(1 for j in jobs if j['status'] in ('SUCCESS', 'FAILURE')))" 2>/dev/null || echo "0")
        if [ "${terminal_count:-0}" -ge "$expected_count" ] 2>/dev/null; then
            return 0
        fi
        sleep 5
        elapsed=$((elapsed + 5))
    done
    return 1
}

print_pipeline_summary() {
    local exec_pipeline_id="$1"
    get_pipeline_jobs "$exec_pipeline_id" | python3 -c "
import sys, json
jobs = sorted(json.load(sys.stdin), key=lambda j: j['jobOrder'])
for j in jobs:
    print(f\"  order={j['jobOrder']} jobName={j['jobName']} status={j['status']} jenkinsId={j['jenkinsInstanceId']}\")" 2>/dev/null
}
