#!/bin/bash
# TC-09: Multi-Pipeline Parallel (2 Pipelines, 6 Jobs, 2 Jenkins)

tc09_multi_pipeline_parallel() {
    echo ""
    echo "=== TC-09: Multi-Pipeline Parallel (2 Pipelines) ==="

    clean_db

    info "Triggering Pipeline A (mixed-3-multi-jenkins)..."
    local exec_a
    exec_a=$(trigger_pipeline "mixed-3-multi-jenkins")
    info "Pipeline A: ${exec_a}"

    info "Triggering Pipeline B (mixed-3-jenkins2-first)..."
    local exec_b
    exec_b=$(trigger_pipeline "mixed-3-jenkins2-first")
    info "Pipeline B: ${exec_b}"

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

    info "Pipeline A summary:"
    print_pipeline_summary "$exec_a"
    info "Pipeline B summary:"
    print_pipeline_summary "$exec_b"

    local success_a success_b
    success_a=$(get_pipeline_jobs "$exec_a" | python3 -c "
import sys, json; print(sum(1 for j in json.load(sys.stdin) if j['status']=='SUCCESS'))" 2>/dev/null || echo "0")
    success_b=$(get_pipeline_jobs "$exec_b" | python3 -c "
import sys, json; print(sum(1 for j in json.load(sys.stdin) if j['status']=='SUCCESS'))" 2>/dev/null || echo "0")
    assert_eq "$success_a" "3" "Pipeline A SUCCESS count"
    assert_eq "$success_b" "3" "Pipeline B SUCCESS count"

    echo ""
}
