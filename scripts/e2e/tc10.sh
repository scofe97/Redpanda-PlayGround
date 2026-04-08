#!/bin/bash
# TC-10: Pipeline Mid-Failure Propagation

tc10_pipeline_mid_failure() {
    echo ""
    echo "=== TC-10: Pipeline Mid-Failure (Jenkins-2 down) ==="

    warn "This test will stop Docker Jenkins-2!"
    read -p "Continue? (y/N) " confirm
    if [[ "${confirm,,}" != "y" ]]; then
        warn "Skipping TC-10."
        return 0
    fi

    clean_db

    info "Stopping Docker Jenkins-2..."
    gcloud compute ssh dev-server-3 --zone=asia-northeast3-a --command="docker stop playground-jenkins-2" 2>/dev/null
    sleep 5

    info "Triggering mixed-3-multi-jenkins pipeline..."
    local exec_id
    exec_id=$(trigger_pipeline "mixed-3-multi-jenkins")
    info "executionPipelineId = ${exec_id}"

    info "Waiting for pipeline to settle (timeout=120s)..."
    sleep 90

    info "Pipeline summary:"
    print_pipeline_summary "$exec_id"

    local job1_status
    job1_status=$(get_pipeline_jobs "$exec_id" | python3 -c "
import sys, json
jobs = sorted(json.load(sys.stdin), key=lambda j: j['jobOrder'])
print(jobs[0]['status'] if jobs else 'UNKNOWN')" 2>/dev/null || echo "UNKNOWN")
    assert_eq "$job1_status" "SUCCESS" "Job 1 (Jenkins-1) status"

    local job2_status
    job2_status=$(get_pipeline_jobs "$exec_id" | python3 -c "
import sys, json
jobs = sorted(json.load(sys.stdin), key=lambda j: j['jobOrder'])
print(jobs[1]['status'] if len(jobs)>1 else 'UNKNOWN')" 2>/dev/null || echo "UNKNOWN")
    assert_eq "$job2_status" "FAILURE" "Job 2 (Jenkins-2) status"

    local job3_status
    job3_status=$(get_pipeline_jobs "$exec_id" | python3 -c "
import sys, json
jobs = sorted(json.load(sys.stdin), key=lambda j: j['jobOrder'])
print(jobs[2]['status'] if len(jobs)>2 else 'UNKNOWN')" 2>/dev/null || echo "UNKNOWN")
    assert_eq "$job3_status" "PENDING" "Job 3 (not dispatched) status"

    info "Restoring Docker Jenkins-2..."
    gcloud compute ssh dev-server-3 --zone=asia-northeast3-a --command="docker start playground-jenkins-2" 2>/dev/null
    sleep 10

    echo ""
}
