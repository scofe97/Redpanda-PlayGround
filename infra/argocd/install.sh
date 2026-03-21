#!/usr/bin/env bash
# ============================================================================
# ArgoCD Application 등록 스크립트
# ============================================================================
# 모든 인프라 컴포넌트를 ArgoCD Application으로 등록한다.
# ArgoCD가 이미 설치되어 있어야 한다.
#
# 사용법:
#   chmod +x install.sh
#   ./install.sh          # 전체 등록
#   ./install.sh delete   # 전체 삭제
# ============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ACTION="${1:-apply}"

if [ "${ACTION}" = "delete" ]; then
    echo "=== ArgoCD Applications 삭제 ==="
    kubectl delete -f "${SCRIPT_DIR}/" --ignore-not-found
    echo "삭제 완료"
    exit 0
fi

echo "=== ArgoCD Applications 등록 ==="

# AppProject 먼저 생성
echo "[1/2] AppProject 등록..."
kubectl apply -f "${SCRIPT_DIR}/project.yaml"

# 모든 Application 등록
echo "[2/2] Applications 등록..."
for f in "${SCRIPT_DIR}"/*.yaml; do
    [ "$(basename "$f")" = "project.yaml" ] && continue
    echo "  - $(basename "$f" .yaml)"
    kubectl apply -f "$f"
done

echo ""
echo "=== 등록 완료 ==="
echo ""
echo "확인:"
echo "  argocd app list"
echo "  kubectl get applications -n argocd"
echo ""
echo "ArgoCD UI: http://34.47.83.38:31134"
