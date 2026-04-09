#!/bin/bash
# ==========================================================================
# TC-06: Idempotency (중복 메시지 재발행)
#
# 이미 처리된 Job의 dispatch payload를 Kafka에 다시 발행했을 때,
# Executor가 멱등하게 처리하는지 (기존 Job 유지, 중복 삽입 없음) 검증한다.
#
# 방법: operator.outbox_event의 JOB_DISPATCH payload(hex) 조회 →
#      rpk topic produce로 동일 payload 재발행
#
# 검증 항목:
#   1) 재발행 전/후 executor.execution_job row count가 1로 동일
#   2) Job 상태가 SUCCESS로 유지
#
# 선행 조건: Jenkins-1 정상 가동, executor-test 파이프라인 존재
# 자동화 가능: YES
# ==========================================================================

tc06_idempotency() {
    echo ""
    echo "=== TC-06: Idempotency (re-publish duplicate command) ==="

    clean_db

    # Step 1: 기준 Job 1건을 정상 처리
    info "Triggering baseline job..."
    local job_excn_id
    job_excn_id=$(trigger_job "executor-test" "1")
    info "jobExcnId = ${job_excn_id}"

    info "Waiting for baseline SUCCESS (timeout=120s)..."
    local final_status
    final_status=$(wait_for_status "$job_excn_id" "SUCCESS" 120) || true
    assert_eq "$final_status" "SUCCESS" "Baseline executor job status"

    # Step 2: 원본 dispatch payload(hex) 조회
    info "Fetching original JOB_DISPATCH payload (hex)..."
    local payload_hex
    payload_hex=$($GCP_SSH "kubectl exec -n rp-oss postgresql-0 -- env PGPASSWORD=playground psql -U playground -d playground -t -c \"SELECT encode(payload, 'hex') FROM operator.outbox_event WHERE aggregate_id='${job_excn_id}' AND event_type='JOB_DISPATCH' ORDER BY id DESC LIMIT 1;\"" 2>/dev/null | tr -d ' \n')

    if [ -z "$payload_hex" ]; then
        fail "Dispatch payload not found for jobExcnId=${job_excn_id}"
        echo ""
        return
    fi
    pass "Dispatch payload fetched (bytes=$((${#payload_hex} / 2)))"

    # Step 3: 재발행 전 row count 확인
    local before_count
    before_count=$($GCP_SSH "kubectl exec -n rp-oss postgresql-0 -- env PGPASSWORD=playground psql -U playground -d playground -t -c \"SELECT COUNT(*) FROM executor.execution_job WHERE job_excn_id='${job_excn_id}';\"" 2>/dev/null | tr -d ' \n')
    assert_eq "${before_count:-0}" "1" "Execution row count before replay"

    # Step 4: 동일 payload 재발행
    info "Re-publishing same dispatch payload..."
    if $GCP_SSH "kubectl exec -n rp-oss redpanda-0 -- sh -lc \"printf '%s\n' '${payload_hex}' | rpk topic produce playground.executor.commands.job-dispatch -f '%v{hex}\n' >/dev/null\" " > /dev/null 2>&1; then
        pass "Duplicate command re-published"
    else
        fail "Failed to re-publish duplicate command"
        echo ""
        return
    fi

    sleep 8

    # Step 5: 재발행 후 row count / 상태 확인
    local after_count
    after_count=$($GCP_SSH "kubectl exec -n rp-oss postgresql-0 -- env PGPASSWORD=playground psql -U playground -d playground -t -c \"SELECT COUNT(*) FROM executor.execution_job WHERE job_excn_id='${job_excn_id}';\"" 2>/dev/null | tr -d ' \n')
    assert_eq "${after_count:-0}" "1" "Execution row count after replay"

    local status_after
    status_after=$(curl -s "${EXECUTOR_URL}/api/executor/jobs/${job_excn_id}" \
        | python3 -c "import sys,json; print(json.load(sys.stdin).get('status',''))" 2>/dev/null || echo "")
    assert_eq "$status_after" "SUCCESS" "Executor job status after replay"

    echo ""
}
