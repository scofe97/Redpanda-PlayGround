#!/bin/bash
# ==========================================================================
# E2E 공통 환경변수, 유틸리티, 헬퍼 함수
#
# 모든 TC 스크립트가 source로 로드하는 공통 모듈이다.
# 크게 4가지 영역으로 구성된다:
#   1) 출력 헬퍼   — 색상 코드 기반 로그 + assertion 유틸
#   2) 인프라 점검 — Executor/Operator/DB/Kafka 헬스 체크 + DB 초기화
#   3) 단일 Job    — Operator API로 Job 트리거 → Executor API로 상태 폴링
#   4) Pipeline    — Pipeline 실행 → 전체 Job 완료 대기 → 결과 요약 출력
# ==========================================================================

# ---------------------------------------------------------------------------
# 터미널 색상 코드 (ANSI escape)
# ---------------------------------------------------------------------------
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'

# ---------------------------------------------------------------------------
# 서비스 엔드포인트
#   - EXECUTOR_URL : executor 모듈 (Job 실행/상태 조회)
#   - OPERATOR_URL : operator-stub 모듈 (Job/Pipeline 트리거)
#   - GCP_SSH      : GCP dev-server에 SSH로 kubectl 명령을 원격 실행하는 프리픽스
#   - LOG_PATH     : executor가 로컬에 남기는 빌드 로그 디렉토리
# ---------------------------------------------------------------------------
EXECUTOR_URL="http://localhost:8071"
OPERATOR_URL="http://localhost:8072"
GCP_SSH="gcloud compute ssh dev-server --zone=asia-northeast3-a --project=project-a99c4fa1-6c9e-4491-afd --command"
LOG_PATH="/tmp/executor-logs"

# ---------------------------------------------------------------------------
# 출력 헬퍼 — TC 실행 로그를 색상으로 구분
# ---------------------------------------------------------------------------

info() { echo -e "${BLUE}[INFO]${NC} $1"; }
pass() { echo -e "${GREEN}[PASS]${NC} $1"; }
fail() { echo -e "${RED}[FAIL]${NC} $1"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }

# 값 동등 비교. actual == expected이면 PASS, 아니면 FAIL 출력.
assert_eq() {
    local actual="$1" expected="$2" msg="$3"
    if [ "$actual" = "$expected" ]; then
        pass "$msg: $actual"
    else
        fail "$msg: expected=$expected, actual=$actual"
    fi
}

# 문자열 포함 비교. haystack에 needle이 있으면 PASS.
assert_contains() {
    local haystack="$1" needle="$2" msg="$3"
    if echo "$haystack" | grep -q "$needle"; then
        pass "$msg"
    else
        fail "$msg: '$needle' not found"
    fi
}

# ---------------------------------------------------------------------------
# 인프라 점검 — TC 실행 전 4가지 필수 의존성 확인
#   1) Executor health    (localhost:8071)
#   2) Operator-stub health (localhost:8072)
#   3) PostgreSQL 접근성  (GCP K8s NodePort 30275)
#   4) Redpanda 접근성    (GCP K8s NodePort 31092)
# 하나라도 실패하면 스크립트를 중단한다.
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

# DB 초기화 — executor/operator_stub 스키마의 테이블과 로컬 로그를 전부 삭제.
# TC 간 데이터 간섭을 방지하기 위해 매 TC 시작 전 호출한다.
clean_db() {
    info "Cleaning DB..."
    $GCP_SSH "kubectl exec -n rp-oss postgresql-0 -- env PGPASSWORD=playground psql -U playground -d playground -c 'DELETE FROM executor.execution_job; DELETE FROM executor.outbox_event; DELETE FROM operator_stub.operator_job;'" > /dev/null 2>&1
    rm -rf ${LOG_PATH}/*
    info "DB and logs cleaned"
}

# ---------------------------------------------------------------------------
# 단일 Job 헬퍼
# ---------------------------------------------------------------------------

# Operator API를 통해 Job을 트리거하고, 생성된 operatorJobId를 반환한다.
# Args: $1=jobName (기본 "executor-test"), $2=jenkinsInstanceId (기본 "1")
trigger_job() {
    local job_name="${1:-executor-test}" jenkins_id="${2:-1}"
    local response
    response=$(curl -s -X POST "${OPERATOR_URL}/api/operator/jobs/execute?jobName=${job_name}&jenkinsInstanceId=${jenkins_id}")
    echo "$response" | python3 -c "import sys,json; print(json.load(sys.stdin)['operatorJobId'])" 2>/dev/null
}

# Executor API를 폴링하여 Job이 기대 상태에 도달할 때까지 대기한다.
# 3초 간격으로 폴링하며, 타임아웃 초과 시 마지막 상태를 반환하고 exit 1.
# Args: $1=jobExcnId, $2=expectedStatus, $3=timeout(초, 기본 90)
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

# Executor API에서 Job 전체 JSON을 조회한다.
get_executor_job() {
    local job_excn_id="$1"
    curl -s "${EXECUTOR_URL}/api/executor/jobs/${job_excn_id}"
}

# Operator API에서 특정 operatorJobId의 status를 추출한다.
# 전체 Job 목록을 조회한 뒤 Python으로 ID 매칭하여 상태만 출력.
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
# Pipeline 헬퍼
# ---------------------------------------------------------------------------

# Operator API를 통해 Pipeline을 실행하고, executionPipelineId를 반환한다.
# Args: $1=pipelineId (Pipeline Definition ID)
trigger_pipeline() {
    local pipeline_id="$1"
    local response
    response=$(curl -s -X POST "${OPERATOR_URL}/api/operator/pipelines/${pipeline_id}/execute")
    echo "$response" | python3 -c "import sys,json; print(json.load(sys.stdin)['executionPipelineId'])" 2>/dev/null
}

# Pipeline에 속한 전체 Job 목록을 JSON으로 조회한다.
get_pipeline_jobs() {
    local exec_pipeline_id="$1"
    curl -s "${OPERATOR_URL}/api/operator/jobs?executionPipelineId=${exec_pipeline_id}"
}

# Pipeline의 모든 Job이 터미널 상태(SUCCESS/FAILURE)에 도달할 때까지 대기.
# 5초 간격 폴링. 기대 Job 수(expected_count)만큼 완료되면 성공 반환.
# Args: $1=executionPipelineId, $2=expectedCount, $3=timeout(초, 기본 180)
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

# Pipeline 실행 결과를 jobOrder 순서로 한 줄씩 출력한다.
# 출력 형식: order=N jobName=... status=... jenkinsId=...
print_pipeline_summary() {
    local exec_pipeline_id="$1"
    get_pipeline_jobs "$exec_pipeline_id" | python3 -c "
import sys, json
jobs = sorted(json.load(sys.stdin), key=lambda j: j['jobOrder'])
for j in jobs:
    print(f\"  order={j['jobOrder']} jobName={j['jobName']} status={j['status']} jenkinsId={j['jenkinsInstanceId']}\")" 2>/dev/null
}
