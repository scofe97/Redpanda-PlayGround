#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SAMPLE_DIR="$(cd "$SCRIPT_DIR/../sample-apps" && pwd)"

GITLAB_URL="${GITLAB_URL:-http://localhost:29180}"
GITLAB_PASSWORD="${GITLAB_PASSWORD:-playground1234!}"

echo "=== GitLab Setup ==="

# 1. Wait for GitLab
bash "$SCRIPT_DIR/wait-for-service.sh" "$GITLAB_URL/users/sign_in" 600 "GitLab"

# 2. Create Personal Access Token via rails runner
echo "🔑 Creating Personal Access Token..."
TOKEN=$(docker exec playground-gitlab gitlab-rails runner "
  user = User.find_by_username('root')
  token = user.personal_access_tokens.find_by(name: 'playground-token')
  token&.destroy
  token = user.personal_access_tokens.create!(
    name: 'playground-token',
    scopes: ['api', 'read_repository', 'write_repository'],
    expires_at: 365.days.from_now
  )
  print token.token
" 2>/dev/null)

if [ -z "$TOKEN" ]; then
    echo "❌ Failed to create GitLab token"
    exit 1
fi
echo "✅ Token created: ${TOKEN:0:8}..."

# --- Helper function: upload file via API ---
upload_file() {
    local project_id=$1
    local file_path=$2
    local local_file=$3
    local branch=${4:-main}
    local content
    content=$(base64 < "$local_file")

    curl -sf -o /dev/null -X POST "$GITLAB_URL/api/v4/projects/$project_id/repository/files/$(echo "$file_path" | sed 's|/|%2F|g')" \
        -H "PRIVATE-TOKEN: $TOKEN" \
        -H "Content-Type: application/json" \
        -d "{
            \"branch\": \"$branch\",
            \"content\": \"$content\",
            \"encoding\": \"base64\",
            \"commit_message\": \"Add $file_path\"
        }" || echo "  ⚠️  $file_path may already exist, skipping"
}

# --- egov-sample ---
echo ""
echo "📦 Creating egov-sample project..."
EGOV_ID=$(curl -sf -X POST "$GITLAB_URL/api/v4/projects" \
    -H "PRIVATE-TOKEN: $TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"name":"egov-sample","initialize_with_readme":true,"default_branch":"main"}' \
    | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])" 2>/dev/null || echo "")

if [ -z "$EGOV_ID" ]; then
    echo "  Project may already exist, fetching ID..."
    EGOV_ID=$(curl -sf "$GITLAB_URL/api/v4/projects?search=egov-sample" \
        -H "PRIVATE-TOKEN: $TOKEN" \
        | python3 -c "import sys,json; print(json.load(sys.stdin)[0]['id'])")
fi
echo "  ✅ egov-sample project ID: $EGOV_ID"

echo "  📄 Uploading egov-sample files..."
upload_file "$EGOV_ID" "pom.xml" "$SAMPLE_DIR/egov-sample/pom.xml"
upload_file "$EGOV_ID" "src/main/java/com/example/egov/EgovApp.java" "$SAMPLE_DIR/egov-sample/src/main/java/com/example/egov/EgovApp.java"
upload_file "$EGOV_ID" "src/main/webapp/WEB-INF/web.xml" "$SAMPLE_DIR/egov-sample/src/main/webapp/WEB-INF/web.xml"
upload_file "$EGOV_ID" "Dockerfile" "$SAMPLE_DIR/egov-sample/Dockerfile"

# --- portal-app ---
echo ""
echo "📦 Creating portal-app project..."
PORTAL_ID=$(curl -sf -X POST "$GITLAB_URL/api/v4/projects" \
    -H "PRIVATE-TOKEN: $TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"name":"portal-app","initialize_with_readme":true,"default_branch":"main"}' \
    | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])" 2>/dev/null || echo "")

if [ -z "$PORTAL_ID" ]; then
    echo "  Project may already exist, fetching ID..."
    PORTAL_ID=$(curl -sf "$GITLAB_URL/api/v4/projects?search=portal-app" \
        -H "PRIVATE-TOKEN: $TOKEN" \
        | python3 -c "import sys,json; print(json.load(sys.stdin)[0]['id'])")
fi
echo "  ✅ portal-app project ID: $PORTAL_ID"

echo "  📄 Uploading portal-app files..."
upload_file "$PORTAL_ID" "pom.xml" "$SAMPLE_DIR/portal-app/pom.xml"
upload_file "$PORTAL_ID" "src/main/java/com/example/portal/PortalApp.java" "$SAMPLE_DIR/portal-app/src/main/java/com/example/portal/PortalApp.java"
upload_file "$PORTAL_ID" "Dockerfile" "$SAMPLE_DIR/portal-app/Dockerfile"

# Create develop branch for portal-app
echo "  🌿 Creating develop branch for portal-app..."
curl -sf -o /dev/null -X POST "$GITLAB_URL/api/v4/projects/$PORTAL_ID/repository/branches" \
    -H "PRIVATE-TOKEN: $TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"branch":"develop","ref":"main"}' || echo "  ⚠️  develop branch may already exist"

echo ""
echo "=== GitLab Setup Complete ==="
echo "  GitLab URL: $GITLAB_URL"
echo "  Token: $TOKEN"
echo "  Projects: egov-sample ($EGOV_ID), portal-app ($PORTAL_ID)"
