#!/usr/bin/env bash
# ============================================================================
# OSS 스택 설치 스크립트 (Redpanda, PostgreSQL, Connect, Console, Playground)
# ============================================================================
# 설치 순서: PostgreSQL → Redpanda → Connect → Console → Playground App → Frontend
# Redpanda가 준비된 후 Connect/Console을 설치해야 한다.
# Playground App은 PostgreSQL + Redpanda가 준비된 후 설치한다.
#
# 사용법:
#   chmod +x oss-install.sh
#   ./oss-install.sh          # 전체 설치
#   ./oss-install.sh upgrade  # 전체 업그레이드
# ============================================================================

set -euo pipefail

NAMESPACE="rp-oss"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ACTION="${1:-install}"

echo "=== OSS 스택 ${ACTION} ==="
echo "Namespace: ${NAMESPACE}"
echo ""

# 네임스페이스
if ! kubectl get namespace "${NAMESPACE}" &>/dev/null; then
    echo "[1/7] Creating namespace ${NAMESPACE}..."
    kubectl create namespace "${NAMESPACE}"
else
    echo "[1/7] Namespace ${NAMESPACE} exists"
fi

# Helm repo
echo "[2/7] Adding Helm repos..."
helm repo add redpanda https://charts.redpanda.com 2>/dev/null || true
helm repo add bitnami https://charts.bitnami.com/bitnami 2>/dev/null || true
helm repo update

# PostgreSQL
echo "[3/7] ${ACTION} PostgreSQL..."
helm "${ACTION}" postgresql bitnami/postgresql \
    -n "${NAMESPACE}" \
    -f "${SCRIPT_DIR}/postgresql/values.yaml" \
    --wait --timeout 5m

# Redpanda
echo "[4/7] ${ACTION} Redpanda..."
helm "${ACTION}" redpanda redpanda/redpanda \
    -n "${NAMESPACE}" \
    -f "${SCRIPT_DIR}/redpanda/values.yaml" \
    --wait --timeout 10m

# Connect (kubectl manifests)
echo "[5/7] Applying Connect manifests..."
kubectl apply -f "${SCRIPT_DIR}/connect/manifests.yaml"
echo "  Waiting for Connect to be ready..."
kubectl rollout status deployment/connect -n "${NAMESPACE}" --timeout=3m || true

# Console
echo "[5.5/7] ${ACTION} Console..."
helm "${ACTION}" console redpanda/console \
    -n "${NAMESPACE}" \
    -f "${SCRIPT_DIR}/console/values.yaml" \
    --wait --timeout 5m

# Playground App
echo "[6/7] ${ACTION} Playground App..."
helm "${ACTION}" playground "${SCRIPT_DIR}/playground" \
    -n "${NAMESPACE}" \
    --wait --timeout 5m

# Playground Frontend
echo "[7/7] ${ACTION} Playground Frontend..."
helm "${ACTION}" playground-frontend "${SCRIPT_DIR}/playground-frontend" \
    -n "${NAMESPACE}" \
    --wait --timeout 3m

echo ""
echo "=== 설치 완료 ==="
echo ""
echo "검증:"
echo "  kubectl get pods -n ${NAMESPACE}"
echo "  kubectl get svc -n ${NAMESPACE}"
echo ""
echo "Console: http://34.22.78.240:31080"
echo "Redpanda Kafka: 34.47.83.38:31092"
echo "Playground API: http://<NODE_IP>:31070"
echo "Playground UI:  http://<NODE_IP>:31080"
