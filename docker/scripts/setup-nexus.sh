#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SAMPLE_DIR="$(cd "$SCRIPT_DIR/../sample-apps" && pwd)"

NEXUS_URL="${NEXUS_URL:-http://localhost:28881}"
NEXUS_USER="${NEXUS_USER:-admin}"
NEXUS_NEW_PASSWORD="${NEXUS_NEW_PASSWORD:-playground1234!}"

echo "=== Nexus Setup ==="

# 1. Wait for Nexus
bash "$SCRIPT_DIR/wait-for-service.sh" "$NEXUS_URL/service/rest/v1/status" 300 "Nexus"

# 2. Get initial admin password
echo "🔑 Getting initial admin password..."
INIT_PASSWORD=$(docker exec playground-nexus cat /nexus-data/admin.password 2>/dev/null || echo "")

if [ -n "$INIT_PASSWORD" ]; then
    echo "  Initial password found, changing to playground password..."
    curl -sf -o /dev/null -X PUT "$NEXUS_URL/service/rest/v1/security/users/admin/change-password" \
        -u "$NEXUS_USER:$INIT_PASSWORD" \
        -H "Content-Type: text/plain" \
        -d "$NEXUS_NEW_PASSWORD"
    echo "  ✅ Password changed"

    # Disable anonymous access
    curl -sf -o /dev/null -X PUT "$NEXUS_URL/service/rest/v1/security/anonymous" \
        -u "$NEXUS_USER:$NEXUS_NEW_PASSWORD" \
        -H "Content-Type: application/json" \
        -d '{"enabled":true,"userId":"anonymous","realmName":"NexusAuthorizingRealm"}'
else
    echo "  Using existing password (already initialized)"
fi

PASSWORD="$NEXUS_NEW_PASSWORD"

# 3. Create maven-releases hosted repo (if not exists)
echo ""
echo "📦 Creating maven-releases repository..."
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
    }' || echo "  ⚠️  Repository may already exist"
echo "  ✅ maven-releases repository ready"

# 4. Create a dummy WAR and upload
echo ""
echo "📄 Uploading egov-sample WAR artifact..."

# Create a minimal WAR file (zip with WEB-INF/web.xml)
TMPWAR=$(mktemp /tmp/egov-sample-XXXXX.war)
cd "$SAMPLE_DIR/egov-sample/src/main/webapp" && zip -q "$TMPWAR" WEB-INF/web.xml && cd - > /dev/null

# Upload via Maven repository path
curl -sf -o /dev/null -u "$NEXUS_USER:$PASSWORD" \
    --upload-file "$TMPWAR" \
    "$NEXUS_URL/repository/maven-releases/com/example/egov-sample/1.0.0/egov-sample-1.0.0.war" \
    || echo "  ⚠️  Artifact may already exist"

# Upload POM
curl -sf -o /dev/null -u "$NEXUS_USER:$PASSWORD" \
    --upload-file "$SAMPLE_DIR/egov-sample/pom.xml" \
    "$NEXUS_URL/repository/maven-releases/com/example/egov-sample/1.0.0/egov-sample-1.0.0.pom" \
    || echo "  ⚠️  POM may already exist"

rm -f "$TMPWAR"
echo "  ✅ egov-sample:1.0.0:war uploaded"

echo ""
echo "=== Nexus Setup Complete ==="
echo "  Nexus URL: $NEXUS_URL"
echo "  Credentials: $NEXUS_USER / $NEXUS_NEW_PASSWORD"
echo "  Artifact: com.example:egov-sample:1.0.0:war"
