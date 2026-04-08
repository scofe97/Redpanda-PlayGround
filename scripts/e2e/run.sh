#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Load common helpers
source "${SCRIPT_DIR}/common.sh"

# Load all TC files
for tc_file in "${SCRIPT_DIR}"/tc*.sh; do
    source "$tc_file"
done

main() {
    echo "=================================="
    echo " Executor PoC E2E Test Suite"
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
            echo "  tc06      Idempotency (re-consume via offset reset)"
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
