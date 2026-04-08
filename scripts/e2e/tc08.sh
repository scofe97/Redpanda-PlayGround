#!/bin/bash
# ==========================================================================
# TC-08: Mixed Pipeline (단일 파이프라인, 멀티 Jenkins)
#
# 하나의 Pipeline에 3개 Job이 있고, 각 Job이 서로 다른 Jenkins 인스턴스에
# 분배되는 시나리오를 검증한다.
# Pipeline Definition "mixed-3-multi-jenkins"는 Jenkins-1과 Jenkins-2를 혼합.
#
# 검증 항목:
#   1) 3개 Job 모두 SUCCESS
#   2) Jenkins-1과 Jenkins-2 양쪽 모두 사용됨 (jenkinsInstanceId 확인)
#   3) 빌드 로그 파일 3개 이상 존재
#
# 선행 조건: Jenkins-1, Jenkins-2(Docker) 모두 가동
# 자동화 가능: YES
# ==========================================================================

tc08_mixed_pipeline() {
    echo ""
    echo "=== TC-08: Mixed Pipeline (Multi-Jenkins) ==="

    clean_db

    # Pipeline 실행 트리거 → executionPipelineId 획득
    info "Triggering mixed-3-multi-jenkins pipeline..."
    local exec_id
    exec_id=$(trigger_pipeline "mixed-3-multi-jenkins")
    info "executionPipelineId = ${exec_id}"

    # 3개 Job이 모두 터미널 상태(SUCCESS/FAILURE)에 도달할 때까지 대기
    info "Waiting for all 3 jobs to complete (timeout=180s)..."
    if wait_for_pipeline_complete "$exec_id" 3 180; then
        pass "All jobs reached terminal state"
    else
        fail "Timeout waiting for pipeline completion"
    fi

    info "Pipeline summary:"
    print_pipeline_summary "$exec_id"

    # 검증 1: 3개 모두 SUCCESS
    local success_count
    success_count=$(get_pipeline_jobs "$exec_id" | python3 -c "
import sys, json
jobs = json.load(sys.stdin)
print(sum(1 for j in jobs if j['status'] == 'SUCCESS'))" 2>/dev/null || echo "0")
    assert_eq "$success_count" "3" "SUCCESS job count"

    # 검증 2: Jenkins-1(id=1)과 Jenkins-2(id=6) 양쪽 모두 사용 확인
    local jenkins_ids
    jenkins_ids=$(get_pipeline_jobs "$exec_id" | python3 -c "
import sys, json
jobs = json.load(sys.stdin)
ids = sorted(set(j['jenkinsInstanceId'] for j in jobs))
print(','.join(str(i) for i in ids))" 2>/dev/null || echo "")
    assert_contains "$jenkins_ids" "1" "Jenkins-1 used"
    assert_contains "$jenkins_ids" "6" "Jenkins-2 used"

    # 검증 3: 빌드 로그 파일 존재
    local log_count
    log_count=$(find "${LOG_PATH}" -name "*_0" -type f 2>/dev/null | wc -l | tr -d ' ')
    if [ "${log_count:-0}" -ge 3 ] 2>/dev/null; then
        pass "Log files exist (count=${log_count})"
    else
        fail "Expected >= 3 log files, found ${log_count}"
    fi

    echo ""
}
