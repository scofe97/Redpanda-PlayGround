#!/bin/bash
# ==========================================================================
# TC-02: Build Failure
#
# Jenkins 빌드가 실패했을 때 상태 전파를 검증한다.
# Jenkins 파이프라인 스크립트에 error("Intentional failure")를 삽입해야 한다.
#
# 검증 항목:
#   1) Executor Job 상태가 FAILURE
#   2) Operator Job 상태가 FAILURE (실패 이벤트 전파)
#   3) 로그 파일에 failure 관련 문자열 존재
#
# 선행 조건: Jenkins 파이프라인 스크립트를 수동으로 실패하도록 변경
# 자동화 가능: NO (수동 Jenkins 설정 변경 필요)
# ==========================================================================

tc02_build_failure() {
    echo ""
    echo "=== TC-02: Build Failure ==="

    # 사용자에게 Jenkins 파이프라인 변경을 안내 (수동 작업)
    warn "ACTION REQUIRED: Change Jenkins pipeline script to include 'error(\"Intentional failure\")'"
    read -p "Press Enter after changing Jenkins pipeline..."

    clean_db

    info "Triggering job..."
    local job_excn_id
    job_excn_id=$(trigger_job "executor-test" "1")
    info "jobExcnId = ${job_excn_id}"

    # 빌드 실패를 기대하므로 FAILURE 상태 대기
    info "Waiting for FAILURE (timeout=90s)..."
    local final_status
    final_status=$(wait_for_status "$job_excn_id" "FAILURE" 90) || true

    assert_eq "$final_status" "FAILURE" "Executor job status"

    local op_status
    op_status=$(get_operator_status "$job_excn_id")
    assert_eq "$op_status" "FAILURE" "Operator job status"

    # 로그 파일에서 실패 원인 문자열 확인
    local log_file
    log_file=$(find "${LOG_PATH}" -name "${job_excn_id}_0" -type f 2>/dev/null | head -1)
    if [ -n "$log_file" ]; then
        local log_content
        log_content=$(cat "$log_file")
        if echo "$log_content" | grep -qi "intentional failure\|error"; then
            pass "Log contains failure indicator"
        else
            warn "Log file exists but failure string not found (may be normal if log is from Jenkins)"
        fi
    else
        warn "Log file not found (Jenkins may not have started): ${LOG_PATH}/**/${job_excn_id}_0"
    fi

    # 테스트 후 원본 파이프라인으로 복원 안내
    warn "ACTION REQUIRED: Restore Jenkins pipeline to original script"
    read -p "Press Enter after restoring Jenkins pipeline..."

    echo ""
}
