#!/usr/bin/env bash
# ============================================================================
# Nexus K8s 초기 설정 스크립트
# ============================================================================
# 1. Nexus Ready 대기
# 2. 초기 admin 비밀번호 변경
# 3. maven-releases (hosted) 저장소 생성
# 4. maven-central (proxy) 저장소 생성
# 5. egov-sample WAR 업로드
# ============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
SAMPLE_DIR="$PROJECT_ROOT/infra/docker/shared/sample-apps"

NEXUS_URL="${NEXUS_URL:-http://34.47.83.38:31280}"
NEXUS_USER="${NEXUS_USER:-admin}"
NEXUS_NEW_PASSWORD="${NEXUS_NEW_PASSWORD:-playground1234!}"
NAMESPACE="${NAMESPACE:-rp-oss}"

echo "=== Nexus K8s Setup ==="
echo "  URL: $NEXUS_URL"
echo "  Namespace: $NAMESPACE"
echo ""

# 1. Wait for Nexus
echo "Waiting for Nexus to be ready..."
for i in $(seq 1 60); do
    if curl -sf -o /dev/null "$NEXUS_URL/service/rest/v1/status" 2>/dev/null; then
        echo "  Nexus is ready!"
        break
    fi
    if [ "$i" -eq 60 ]; then
        echo "  ERROR: Nexus not ready after 5 minutes"
        exit 1
    fi
    printf "  [%d/60] waiting...\r" "$i"
    sleep 5
done

# 2. Get initial admin password
echo ""
echo "Getting initial admin password..."
INIT_PASSWORD=$(kubectl exec deploy/nexus -n "$NAMESPACE" -- cat /nexus-data/admin.password 2>/dev/null || echo "")

if [ -n "$INIT_PASSWORD" ]; then
    echo "  Initial password found, changing to playground password..."
    curl -sf -o /dev/null -X PUT "$NEXUS_URL/service/rest/v1/security/users/admin/change-password" \
        -u "$NEXUS_USER:$INIT_PASSWORD" \
        -H "Content-Type: text/plain" \
        -d "$NEXUS_NEW_PASSWORD"
    echo "  Password changed"

    # Enable anonymous access (for read-only artifact download)
    curl -sf -o /dev/null -X PUT "$NEXUS_URL/service/rest/v1/security/anonymous" \
        -u "$NEXUS_USER:$NEXUS_NEW_PASSWORD" \
        -H "Content-Type: application/json" \
        -d '{"enabled":true,"userId":"anonymous","realmName":"NexusAuthorizingRealm"}'
else
    echo "  Using existing password (already initialized)"
fi

PASSWORD="$NEXUS_NEW_PASSWORD"

# 2.5. Accept Community Edition EULA (Nexus 3.90+ 필수)
echo ""
echo "Accepting Community Edition EULA..."
DISCLAIMER=$(curl -sf -u "$NEXUS_USER:$PASSWORD" "$NEXUS_URL/service/rest/v1/system/eula" | python3 -c "import json,sys; print(json.load(sys.stdin).get('disclaimer',''))" 2>/dev/null || echo "")
if [ -n "$DISCLAIMER" ]; then
    curl -sf -o /dev/null -X POST "$NEXUS_URL/service/rest/v1/system/eula" \
        -u "$NEXUS_USER:$PASSWORD" \
        -H "Content-Type: application/json" \
        -d "{\"disclaimer\": $(echo "$DISCLAIMER" | python3 -c "import json,sys; print(json.dumps(sys.stdin.read().strip()))"), \"accepted\": true}" \
        || echo "  EULA may already be accepted"
    echo "  EULA accepted"
else
    echo "  EULA already accepted or not required"
fi

# 3. Create maven-releases hosted repo
echo ""
echo "Creating maven-releases repository..."
curl -sf -o /dev/null -X POST "$NEXUS_URL/service/rest/v1/repositories/maven/hosted" \
    -u "$NEXUS_USER:$PASSWORD" \
    -H "Content-Type: application/json" \
    -d '{
        "name": "maven-releases",
        "online": true,
        "storage": {
            "blobStoreName": "default",
            "strictContentTypeValidation": true,
            "writePolicy": "ALLOW_ONCE"
        },
        "maven": {
            "versionPolicy": "RELEASE",
            "layoutPolicy": "STRICT",
            "contentDisposition": "INLINE"
        }
    }' || echo "  Repository may already exist"
echo "  maven-releases ready"

# 4. Create maven-central proxy repo
echo ""
echo "Creating maven-central proxy repository..."
curl -sf -o /dev/null -X POST "$NEXUS_URL/service/rest/v1/repositories/maven/proxy" \
    -u "$NEXUS_USER:$PASSWORD" \
    -H "Content-Type: application/json" \
    -d '{
        "name": "maven-central",
        "online": true,
        "storage": {
            "blobStoreName": "default",
            "strictContentTypeValidation": true
        },
        "proxy": {
            "remoteUrl": "https://repo1.maven.org/maven2/",
            "contentMaxAge": 1440,
            "metadataMaxAge": 1440
        },
        "negativeCache": {
            "enabled": true,
            "timeToLive": 1440
        },
        "httpClient": {
            "blocked": false,
            "autoBlock": true
        },
        "maven": {
            "versionPolicy": "RELEASE",
            "layoutPolicy": "PERMISSIVE",
            "contentDisposition": "INLINE"
        }
    }' || echo "  Repository may already exist"
echo "  maven-central proxy ready"

# 5. Upload egov-sample WAR
echo ""
echo "Uploading egov-sample WAR artifact..."

if [ -d "$SAMPLE_DIR/egov-sample/src/main/webapp" ]; then
    TMPDIR_WAR=$(mktemp -d)
    TMPWAR="$TMPDIR_WAR/egov-sample-1.0.0.war"
    cd "$SAMPLE_DIR/egov-sample/src/main/webapp" && zip -q "$TMPWAR" WEB-INF/web.xml && cd - > /dev/null

    curl -sf -o /dev/null -u "$NEXUS_USER:$PASSWORD" \
        --upload-file "$TMPWAR" \
        "$NEXUS_URL/repository/maven-releases/com/example/egov-sample/1.0.0/egov-sample-1.0.0.war" \
        || echo "  Artifact may already exist"

    curl -sf -o /dev/null -u "$NEXUS_USER:$PASSWORD" \
        --upload-file "$SAMPLE_DIR/egov-sample/pom.xml" \
        "$NEXUS_URL/repository/maven-releases/com/example/egov-sample/1.0.0/egov-sample-1.0.0.pom" \
        || echo "  POM may already exist"

    rm -rf "$TMPDIR_WAR"
    echo "  egov-sample:1.0.0:war uploaded"
else
    echo "  SKIP: sample-apps directory not found"
fi

echo ""
echo "=== Nexus K8s Setup Complete ==="
echo "  Nexus URL: $NEXUS_URL"
echo "  Credentials: $NEXUS_USER / $NEXUS_NEW_PASSWORD"
echo "  Repositories: maven-releases (hosted), maven-central (proxy)"
