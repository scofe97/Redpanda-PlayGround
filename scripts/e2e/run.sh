#!/bin/bash
# ==========================================================================
# E2E 테스트 실행 진입점
#
# 사용법: ./run.sh {tc이름|그룹|all}
#
# TC를 개별 실행하거나 그룹으로 묶어 실행한다.
# 모든 TC 파일(tc*.sh)을 source로 로드한 뒤, 인자에 따라 해당 함수를 호출.
# 인프라 헬스 체크(check_infra)는 매 실행 시 자동으로 먼저 수행된다.
#
# 그룹:
#   auto     — 완전 자동화 가능한 TC만 (tc01, tc06, tc07)
#   pipeline — Pipeline 관련 TC만 (tc08, tc09)
#   all      — 전체 TC를 권장 순서로 실행
# ==========================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 공통 헬퍼(환경변수, assertion, API 함수) 로드
source "${SCRIPT_DIR}/common.sh"

# 모든 TC 파일을 동적으로 source — tc01.sh ~ tc10.sh
for tc_file in "${SCRIPT_DIR}"/tc*.sh; do
    source "$tc_file"
done

main() {
    echo "=================================="
    echo " Operator + Executor E2E Test Suite"
    echo "=================================="
    echo ""

    case "${1:-all}" in
        check)  check_infra ;;
        tc01)   check_infra; tc01_happy_path ;;
        tc02)   check_infra; tc02_build_failure ;;
        tc03)   check_infra; tc03_trigger_retry ;;
        tc05)   check_infra; tc05_duplicate_prevention ;;
        tc06)   check_infra; tc06_idempotency ;;
        tc07)   check_infra; tc07_multi_trigger ;;
        tc08)   check_infra; tc08_mixed_pipeline ;;
        tc09)   check_infra; tc09_multi_pipeline_parallel ;;
        tc10)   check_infra; tc10_pipeline_mid_failure ;;
        auto)
            check_infra
            tc01_happy_path
            tc06_idempotency
            tc07_multi_trigger
            ;;
        pipeline)
            check_infra
            tc08_mixed_pipeline
            tc09_multi_pipeline_parallel
            ;;
        all)
            # 권장 순서: 기본 → 멱등성 → 동시성 → 파이프라인 → 장애 → 재시도
            # tc03(Jenkins scale-down)은 인프라 변경이 필요하므로 마지막에 실행
            check_infra
            tc01_happy_path
            tc02_build_failure
            tc05_duplicate_prevention
            tc06_idempotency
            tc07_multi_trigger
            tc08_mixed_pipeline
            tc09_multi_pipeline_parallel
            tc10_pipeline_mid_failure
            tc03_trigger_retry
            ;;
        *)
            echo "Usage: $0 {check|tc01|tc02|tc03|tc05|tc06|tc07|tc08|tc09|tc10|auto|pipeline|all}"
            echo ""
            echo "  check     Check infrastructure health"
            echo "  tc01      Happy Path (fully automated)"
            echo "  tc02      Build Failure (requires Jenkins pipeline change)"
            echo "  tc03      Trigger Retry / Jenkins scale-down"
            echo "  tc05      Duplicate Prevention (requires Jenkins pipeline change)"
            echo "  tc06      Idempotency (re-publish same dispatch payload)"
            echo "  tc07      Multi-Trigger (3 parallel jobs)"
            echo "  tc08      Mixed Pipeline (Multi-Jenkins)"
            echo "  tc09      Multi-Pipeline Parallel (2 pipelines)"
            echo "  tc10      Pipeline Mid-Failure (Jenkins-2 down)"
            echo "  auto      Automated only: tc01, tc06, tc07"
            echo "  pipeline  Pipeline tests: tc08, tc09"
            echo "  all       All TCs in recommended order"
            ;;
    esac
}

main "$@"
