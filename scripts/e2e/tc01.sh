#!/bin/bash
# ==========================================================================
# TC-01: Happy Path (단독 Job)
#
# 가장 기본적인 성공 시나리오를 검증한다.
# 흐름: Operator 트리거 → Executor 수신 → Jenkins 빌드 → 완료 통보
#
# 검증 항목:
#   1) Executor Job 상태가 SUCCESS
#   2) Operator Job 상태가 SUCCESS (이벤트 전파 확인)
#   3) 빌드 로그 파일 존재 ({jobExcnId}_0)
#   4) Outbox 이벤트가 SENT 상태 (Kafka 발행 완료)
#
# 선행 조건: Jenkins-1 정상 가동, executor-test 파이프라인 존재
# 자동화 가능: YES (수동 개입 불필요)
# ==========================================================================

tc01_happy_path() {
    echo ""
    echo "=== TC-01: Happy Path ==="

    clean_db

    # Step 1: Operator API로 Job 트리거 → operatorJobId(=jobExcnId) 획득
    info "Triggering job..."
    local job_excn_id
    job_excn_id=$(trigger_job "executor-test" "1")
    info "jobExcnId = ${job_excn_id}"

    # Step 2: Executor API를 90초간 폴링하여 SUCCESS 대기
    info "Waiting for SUCCESS (timeout=90s)..."
    local final_status
    final_status=$(wait_for_status "$job_excn_id" "SUCCESS" 90) || true

    # Step 3: 검증 — Executor/Operator 양쪽 상태 일치 확인
    assert_eq "$final_status" "SUCCESS" "Executor job status"

    # 완료 이벤트 전파 지연을 고려해 Operator 상태를 짧게 폴링
    local op_status=""
    local op_elapsed=0
    local op_timeout=30
    while [ $op_elapsed -lt $op_timeout ]; do
        op_status=$(get_operator_status "$job_excn_id")
        if [ "$op_status" = "SUCCESS" ]; then
            break
        fi
        sleep 2
        op_elapsed=$((op_elapsed + 2))
    done
    assert_eq "$op_status" "SUCCESS" "Operator job status"

    # Step 4: 빌드 로그 파일 존재 확인 (executor가 Jenkins 콘솔 로그를 저장)
    local log_file
    log_file=$(find "${LOG_PATH}" -name "${job_excn_id}_0" -type f 2>/dev/null | head -1)
    if [ -n "$log_file" ]; then
        pass "Log file exists: $log_file"
    else
        fail "Log file not found: ${LOG_PATH}/**/${job_excn_id}_0"
    fi

    # Step 5: Outbox 테이블에서 SENT 상태 이벤트 존재 확인 (Kafka 발행 증거)
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
