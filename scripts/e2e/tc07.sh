#!/bin/bash
# ==========================================================================
# TC-07: Multi-Trigger (3개 Job 동시 트리거)
#
# 3개의 Job을 동시에(백그라운드 &) 트리거하여 동시성 제어가 작동하는지 검증.
# Executor의 max-concurrent-jobs 설정에 따라 일부 Job은 PENDING/QUEUED로
# 대기해야 한다.
#
# 검증 항목:
#   1) 최소 1개 Job이 활성 상태 (RUNNING/QUEUED/SUCCESS)
#   2) 동시성 제한이 관찰됨 (일부 PENDING 또는 active <= limit)
#   3) 중복 감지 로그 확인
#
# 선행 조건: Jenkins-1 정상 가동
# 자동화 가능: YES
# ==========================================================================

tc07_multi_trigger() {
    echo ""
    echo "=== TC-07: Multi-Trigger (3 parallel jobs) ==="

    clean_db

    # 3개 Job을 백그라운드(&)로 동시에 트리거
    # 각 결과(operatorJobId)를 임시 파일에 저장
    info "Triggering 3 jobs in parallel..."

    trigger_job "executor-test" "1" > /tmp/e2e_job1.id &
    trigger_job "executor-test" "1" > /tmp/e2e_job2.id &
    trigger_job "executor-test" "1" > /tmp/e2e_job3.id &
    wait

    local job1 job2 job3
    job1=$(cat /tmp/e2e_job1.id); job2=$(cat /tmp/e2e_job2.id); job3=$(cat /tmp/e2e_job3.id)
    rm -f /tmp/e2e_job{1,2,3}.id
    info "job1=${job1}, job2=${job2}, job3=${job3}"

    # 스케줄링이 발생할 시간 확보
    info "Waiting 20s to observe scheduling..."
    sleep 20

    # 각 Job의 상태를 조회하여 분류
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

    # 검증 1: 최소 1개는 활성(실행 중이거나 완료)
    local active=$((count_running + count_queued + count_success))
    if [ $active -ge 1 ]; then
        pass "At least 1 job is RUNNING/QUEUED/SUCCESS (active=${active})"
    else
        fail "No active job found — all may be stuck in PENDING"
    fi

    # 검증 2: 동시성 제한 관찰 (일부가 PENDING이거나 active가 제한 이하)
    if [ $count_pending -ge 1 ] || [ $((count_running + count_queued)) -le 1 ]; then
        pass "Concurrency control observed: pending=${count_pending}, running=${count_running}, queued=${count_queued}"
    else
        warn "All 3 jobs are active — check if concurrency limit is enforced"
    fi

    # 검증 3: 중복 감지 로그
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
