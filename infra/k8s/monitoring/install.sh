#!/usr/bin/env bash
# ============================================================================
# LGMT 모니터링 스택 설치 스크립트
# ============================================================================
# 설치 순서: Loki → Tempo → Prometheus → Alloy → Grafana
# Alloy가 Loki/Tempo/Prometheus에 의존하므로 먼저 설치해야 한다.
#
# 사용법:
#   chmod +x install.sh
#   ./install.sh          # 전체 설치
#   ./install.sh upgrade  # 전체 업그레이드
# ============================================================================

set -euo pipefail

NAMESPACE="rp-mgm"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ACTION="${1:-install}"

echo "=== LGMT 모니터링 스택 ${ACTION} ==="
echo "Namespace: ${NAMESPACE}"
echo "Script dir: ${SCRIPT_DIR}"
echo ""

# 네임스페이스 확인/생성
if ! kubectl get namespace "${NAMESPACE}" &>/dev/null; then
    echo "[1/7] Creating namespace ${NAMESPACE}..."
    kubectl create namespace "${NAMESPACE}"
else
    echo "[1/7] Namespace ${NAMESPACE} already exists"
fi

# Helm repo 추가
echo "[2/7] Adding Helm repos..."
helm repo add grafana https://grafana.github.io/helm-charts 2>/dev/null || true
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts 2>/dev/null || true
helm repo update

# Loki
echo "[3/7] ${ACTION} Loki..."
helm "${ACTION}" loki grafana/loki \
    -n "${NAMESPACE}" \
    -f "${SCRIPT_DIR}/loki/values.yaml" \
    --wait --timeout 5m

# Tempo
echo "[4/7] ${ACTION} Tempo..."
helm "${ACTION}" tempo grafana/tempo \
    -n "${NAMESPACE}" \
    -f "${SCRIPT_DIR}/tempo/values.yaml" \
    --wait --timeout 5m

# Prometheus
echo "[5/7] ${ACTION} Prometheus..."
helm "${ACTION}" prometheus prometheus-community/prometheus \
    -n "${NAMESPACE}" \
    -f "${SCRIPT_DIR}/prometheus/values.yaml" \
    --wait --timeout 5m

# Alloy (Loki/Tempo/Prometheus가 준비된 후)
echo "[6/7] ${ACTION} Alloy..."
helm "${ACTION}" alloy grafana/alloy \
    -n "${NAMESPACE}" \
    -f "${SCRIPT_DIR}/alloy/values.yaml" \
    --wait --timeout 5m

# Grafana 대시보드 ConfigMap 생성/업데이트
echo "[6.5/7] Creating Grafana dashboard ConfigMap..."
DASHBOARD_DIR="${SCRIPT_DIR}/grafana/dashboards"
if [ -d "${DASHBOARD_DIR}" ] && [ "$(ls -A "${DASHBOARD_DIR}"/*.json 2>/dev/null)" ]; then
    kubectl create configmap grafana-dashboards \
        -n "${NAMESPACE}" \
        --from-file="${DASHBOARD_DIR}" \
        --dry-run=client -o yaml | kubectl apply -f -
    echo "  Dashboard ConfigMap created/updated"
else
    echo "  No dashboard JSON files found in ${DASHBOARD_DIR}, skipping"
fi

# Grafana
echo "[7/7] ${ACTION} Grafana..."
helm "${ACTION}" grafana grafana/grafana \
    -n "${NAMESPACE}" \
    -f "${SCRIPT_DIR}/grafana/values.yaml" \
    --wait --timeout 5m

echo ""
echo "=== 설치 완료 ==="
echo ""
echo "검증:"
echo "  kubectl get pods -n ${NAMESPACE}"
echo "  kubectl get svc -n ${NAMESPACE}"
echo ""
echo "Grafana: http://34.22.78.240:30000"
echo "  (익명 인증, Admin 권한)"
