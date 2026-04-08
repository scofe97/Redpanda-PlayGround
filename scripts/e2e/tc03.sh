#!/bin/bash
# ==========================================================================
# TC-03: Trigger Retry (Jenkins Scale-Down)
#
# Jenkins가 다운된 상태에서 Job을 트리거하면 재시도 메커니즘이 작동하는지 검증.
# Executor가 Jenkins 호출 실패 시 retryCnt를 증가시키고 PENDING으로 유지해야 한다.
#
# 검증 항목:
#   1) retryCnt >= 1 (최소 1회 재시도 발생)
#   2) Job 상태가 PENDING (Jenkins 다운이므로 진행 불가)
#
# 선행 조건: Jenkins-1 StatefulSet이 K8s에 배포되어 있어야 함
# 자동화 가능: PARTIAL (Jenkins scale 변경은 자동, 확인 프롬프트 있음)
# 주의: 테스트 후 Jenkins를 반드시 복원한다 (replicas=1)
# ==========================================================================

tc03_trigger_retry() {
    echo ""
    echo "=== TC-03: Trigger Retry (Jenkins scale-down) ==="

    # 파괴적 작업이므로 사용자 확인
    warn "This test will scale down Jenkins to 0 replicas!"
    read -p "Continue? (y/N) " confirm
    if [[ "${confirm,,}" != "y" ]]; then
        warn "Skipping TC-03."
        return 0
    fi

    # Jenkins를 0으로 스케일다운하여 인위적 장애 생성
    info "Scaling down Jenkins..."
    $GCP_SSH "kubectl scale statefulset jenkins -n rp-jenkins --replicas=0"
    sleep 10

    clean_db

    # Jenkins가 없는 상태에서 Job 트리거 → 실패 → 재시도 기대
    info "Triggering job while Jenkins is down..."
    local job_excn_id
    job_excn_id=$(trigger_job "executor-test" "1")
    info "jobExcnId = ${job_excn_id}"

    # 재시도가 발생할 시간을 확보 (지수 백오프: 2^0=1s, 2^1=2s, ...)
    info "Waiting 20s for trigger attempt + retry..."
    sleep 20

    # Job JSON에서 retryCnt와 상태를 확인
    local job_json
    job_json=$(get_executor_job "$job_excn_id")

    local retry_cnt
    retry_cnt=$(echo "$job_json" | python3 -c "import sys,json; print(json.load(sys.stdin).get('retryCnt', 0))" 2>/dev/null || echo "0")

    local current_status
    current_status=$(echo "$job_json" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status',''))" 2>/dev/null || echo "")

    if [ "${retry_cnt:-0}" -ge 1 ] 2>/dev/null; then
        pass "retryCnt >= 1: actual=${retry_cnt}"
    else
        fail "retryCnt expected >= 1, got: ${retry_cnt}"
    fi

    # Jenkins 다운이므로 PENDING 상태가 유지되어야 함
    assert_eq "$current_status" "PENDING" "Executor job status while Jenkins down"

    # 테스트 후 Jenkins 복원
    info "Restoring Jenkins (scale up)..."
    $GCP_SSH "kubectl scale statefulset jenkins -n rp-jenkins --replicas=1"
    info "Waiting 60s for Jenkins to be ready..."
    sleep 60

    echo ""
}
