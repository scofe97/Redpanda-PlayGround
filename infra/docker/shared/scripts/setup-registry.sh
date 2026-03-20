#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SAMPLE_DIR="$(cd "$SCRIPT_DIR/../sample-apps" && pwd)"

REGISTRY_URL="${REGISTRY_URL:-http://localhost:25050}"
REGISTRY_HOST="${REGISTRY_HOST:-localhost:25050}"

echo "=== Registry Setup ==="

# 1. Wait for Registry
bash "$SCRIPT_DIR/wait-for-service.sh" "$REGISTRY_URL/v2/" 60 "Registry"

# 2. Build egov-sample image
echo ""
echo "🐳 Building egov-sample image..."

# Create a minimal image (no actual Maven build needed — just a demo image)
TMPDIR=$(mktemp -d)
cat > "$TMPDIR/Dockerfile" << 'DOCKERFILE'
FROM alpine:3.20
LABEL app="egov-sample" version="1.0.0"
RUN echo '{"status":"UP","app":"egov-sample","version":"1.0.0"}' > /health.json
EXPOSE 8080
CMD ["cat", "/health.json"]
DOCKERFILE

docker build -t "$REGISTRY_HOST/egov-sample:1.0.0" "$TMPDIR" -q
rm -rf "$TMPDIR"
echo "  ✅ Image built"

# 3. Push to registry
echo "  📤 Pushing egov-sample:1.0.0..."
docker push "$REGISTRY_HOST/egov-sample:1.0.0" -q
echo "  ✅ Pushed egov-sample:1.0.0"

# 4. Tag and push latest
echo "  📤 Pushing egov-sample:latest..."
docker tag "$REGISTRY_HOST/egov-sample:1.0.0" "$REGISTRY_HOST/egov-sample:latest"
docker push "$REGISTRY_HOST/egov-sample:latest" -q
echo "  ✅ Pushed egov-sample:latest"

# 5. Verify
echo ""
echo "🔍 Verifying..."
TAGS=$(curl -sf "$REGISTRY_URL/v2/egov-sample/tags/list" 2>/dev/null || echo "failed")
echo "  Tags: $TAGS"

echo ""
echo "=== Registry Setup Complete ==="
echo "  Registry: $REGISTRY_HOST"
echo "  Image: egov-sample (1.0.0, latest)"
