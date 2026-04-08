#!/bin/bash
# ==========================================================================
# TC-10: Pipeline Mid-Failure Propagation
#
# Pipeline 실행 도중 Jenkins-2(Docker)를 중지하여, 중간 Job 실패 시
# 파이프라인 실패 전파가 올바르게 동작하는지 검증한다.
#
# 시나리오 (mixed-3-multi-jenkins, Job 3개):
#   Job 1 (Jenkins-1) → SUCCESS (Jenkins-1은 정상)
#   Job 2 (Jenkins-2) → FAILURE (Jenkins-2 중지 상태)
#   Job 3             → PENDING (Job 2 실패로 디스패치되지 않음)
#
# 검증 항목:
#   1) Job 1 = SUCCESS
#   2) Job 2 = FAILURE
#   3) Job 3 = PENDING (실패 전파로 미실행)
#
# 선행 조건: dev-server-3에서 Docker Jenkins-2 컨테이너가 실행 중
# 자동화 가능: PARTIAL (Docker 중지는 자동, 확인 프롬프트 있음)
# 주의: 테스트 후 Jenkins-2를 반드시 재시작한다
# ==========================================================================

tc10_pipeline_mid_failure() {
    echo ""
    echo "=== TC-10: Pipeline Mid-Failure (Jenkins-2 down) ==="

    # 파괴적 작업이므로 사용자 확인
    warn "This test will stop Docker Jenkins-2!"
    read -p "Continue? (y/N) " confirm
    if [[ "${confirm,,}" != "y" ]]; then
        warn "Skipping TC-10."
        return 0
    fi

    clean_db

    # dev-server-3에서 Docker Jenkins-2 컨테이너 중지
    info "Stopping Docker Jenkins-2..."
    gcloud compute ssh dev-server-3 --zone=asia-northeast3-a --command="docker stop playground-jenkins-2" 2>/dev/null
    sleep 5

    # Jenkins-2가 다운된 상태에서 Pipeline 트리거
    info "Triggering mixed-3-multi-jenkins pipeline..."
    local exec_id
    exec_id=$(trigger_pipeline "mixed-3-multi-jenkins")
    info "executionPipelineId = ${exec_id}"

    # Job들이 실행/실패할 시간 확보 (90초 대기)
    info "Waiting for pipeline to settle (timeout=120s)..."
    sleep 90

    info "Pipeline summary:"
    print_pipeline_summary "$exec_id"

    # 검증: jobOrder 순으로 정렬하여 각 Job 상태 확인
    # Job 1 (Jenkins-1): 정상이므로 SUCCESS
    local job1_status
    job1_status=$(get_pipeline_jobs "$exec_id" | python3 -c "
import sys, json
jobs = sorted(json.load(sys.stdin), key=lambda j: j['jobOrder'])
print(jobs[0]['status'] if jobs else 'UNKNOWN')" 2>/dev/null || echo "UNKNOWN")
    assert_eq "$job1_status" "SUCCESS" "Job 1 (Jenkins-1) status"

    # Job 2 (Jenkins-2): 다운 상태이므로 FAILURE
    local job2_status
    job2_status=$(get_pipeline_jobs "$exec_id" | python3 -c "
import sys, json
jobs = sorted(json.load(sys.stdin), key=lambda j: j['jobOrder'])
print(jobs[1]['status'] if len(jobs)>1 else 'UNKNOWN')" 2>/dev/null || echo "UNKNOWN")
    assert_eq "$job2_status" "FAILURE" "Job 2 (Jenkins-2) status"

    # Job 3: Job 2 실패로 디스패치되지 않아 PENDING 유지
    local job3_status
    job3_status=$(get_pipeline_jobs "$exec_id" | python3 -c "
import sys, json
jobs = sorted(json.load(sys.stdin), key=lambda j: j['jobOrder'])
print(jobs[2]['status'] if len(jobs)>2 else 'UNKNOWN')" 2>/dev/null || echo "UNKNOWN")
    assert_eq "$job3_status" "PENDING" "Job 3 (not dispatched) status"

    # 테스트 후 Jenkins-2 복원
    info "Restoring Docker Jenkins-2..."
    gcloud compute ssh dev-server-3 --zone=asia-northeast3-a --command="docker start playground-jenkins-2" 2>/dev/null
    sleep 10

    echo ""
}
