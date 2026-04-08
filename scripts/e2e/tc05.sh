#!/bin/bash
# ==========================================================================
# TC-05: Duplicate Prevention
#
# 동일 Jenkins에서 Job이 실행 중일 때 중복 트리거를 방지하는지 검증.
# Jenkins 파이프라인에 sleep 120을 넣어 Job을 오래 실행 중 상태로 유지한 뒤,
# 같은 Job을 다시 트리거하면 중복으로 감지되어야 한다.
#
# 검증 항목:
#   1) 첫 번째 Job(jobA)이 RUNNING 또는 QUEUED 상태
#   2) 로그에 "duplicate skip" 등 중복 감지 메시지 존재
#
# 선행 조건: Jenkins 파이프라인에 sleep 120 삽입
# 자동화 가능: NO (수동 Jenkins 설정 변경 필요)
# ==========================================================================

tc05_duplicate_prevention() {
    echo ""
    echo "=== TC-05: Duplicate Prevention ==="

    warn "ACTION REQUIRED: Change Jenkins pipeline to 'sleep 120' (or equivalent long-running step)"
    read -p "Press Enter after changing Jenkins pipeline..."

    clean_db

    # Step 1: 첫 번째 Job 트리거 → Jenkins에서 장시간 실행
    info "Triggering first job (jobA)..."
    local job_a
    job_a=$(trigger_job "executor-test" "1")
    info "jobA = ${job_a}"

    # jobA가 RUNNING 상태에 도달할 시간 확보
    info "Waiting 15s for jobA to reach RUNNING/QUEUED..."
    sleep 15

    # Step 2: 같은 Job을 다시 트리거 → 중복 감지 기대
    info "Triggering second job (jobB)..."
    local job_b
    job_b=$(trigger_job "executor-test" "1")
    info "jobB = ${job_b}"

    sleep 10

    # Step 3: jobA가 여전히 실행 중인지 확인
    local status_a
    status_a=$(curl -s "${EXECUTOR_URL}/api/executor/jobs/${job_a}" \
        | python3 -c "import sys,json; print(json.load(sys.stdin).get('status',''))" 2>/dev/null || echo "")

    if [ "$status_a" = "RUNNING" ] || [ "$status_a" = "QUEUED" ]; then
        pass "jobA status is ${status_a} (Jenkins busy)"
    else
        warn "jobA status: ${status_a} (may have completed quickly)"
    fi

    # Step 4: 로그에서 중복 감지 메시지 확인
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
