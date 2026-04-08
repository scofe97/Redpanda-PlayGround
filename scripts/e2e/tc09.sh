#!/bin/bash
# ==========================================================================
# TC-09: Multi-Pipeline Parallel (2개 파이프라인, 6개 Job, 2개 Jenkins)
#
# 2개의 서로 다른 Pipeline을 거의 동시에 실행하여, 파이프라인 간 격리와
# 동시 실행이 올바르게 동작하는지 검증한다.
#   - Pipeline A: mixed-3-multi-jenkins (Jenkins-1 + Jenkins-2)
#   - Pipeline B: mixed-3-jenkins2-first (Jenkins-2 우선)
#
# 검증 항목:
#   1) Pipeline A의 3개 Job 모두 SUCCESS
#   2) Pipeline B의 3개 Job 모두 SUCCESS
#
# 선행 조건: Jenkins-1, Jenkins-2 모두 가동, 두 Pipeline Definition 존재
# 자동화 가능: YES
# ==========================================================================

tc09_multi_pipeline_parallel() {
    echo ""
    echo "=== TC-09: Multi-Pipeline Parallel (2 Pipelines) ==="

    clean_db

    # 2개 Pipeline을 순차적으로 트리거 (거의 동시)
    info "Triggering Pipeline A (mixed-3-multi-jenkins)..."
    local exec_a
    exec_a=$(trigger_pipeline "mixed-3-multi-jenkins")
    info "Pipeline A: ${exec_a}"

    info "Triggering Pipeline B (mixed-3-jenkins2-first)..."
    local exec_b
    exec_b=$(trigger_pipeline "mixed-3-jenkins2-first")
    info "Pipeline B: ${exec_b}"

    # 각 Pipeline의 3개 Job이 모두 완료될 때까지 대기
    info "Waiting for Pipeline A (3 jobs, timeout=180s)..."
    if wait_for_pipeline_complete "$exec_a" 3 180; then
        pass "Pipeline A completed"
    else
        fail "Pipeline A timeout"
    fi

    info "Waiting for Pipeline B (3 jobs, timeout=180s)..."
    if wait_for_pipeline_complete "$exec_b" 3 180; then
        pass "Pipeline B completed"
    else
        fail "Pipeline B timeout"
    fi

    # 결과 요약 출력
    info "Pipeline A summary:"
    print_pipeline_summary "$exec_a"
    info "Pipeline B summary:"
    print_pipeline_summary "$exec_b"

    # 검증: 각 Pipeline에서 3개 모두 SUCCESS
    local success_a success_b
    success_a=$(get_pipeline_jobs "$exec_a" | python3 -c "
import sys, json; print(sum(1 for j in json.load(sys.stdin) if j['status']=='SUCCESS'))" 2>/dev/null || echo "0")
    success_b=$(get_pipeline_jobs "$exec_b" | python3 -c "
import sys, json; print(sum(1 for j in json.load(sys.stdin) if j['status']=='SUCCESS'))" 2>/dev/null || echo "0")
    assert_eq "$success_a" "3" "Pipeline A SUCCESS count"
    assert_eq "$success_b" "3" "Pipeline B SUCCESS count"

    echo ""
}
