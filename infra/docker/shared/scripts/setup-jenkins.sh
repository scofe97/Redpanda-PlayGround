#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

JENKINS_URL="${JENKINS_URL:-http://localhost:29080}"
JENKINS_USER="${JENKINS_USER:-admin}"
# Try initial admin password from container first, fall back to configured password
INIT_PASS=$(docker exec playground-jenkins cat /var/jenkins_home/secrets/initialAdminPassword 2>/dev/null || echo "")
JENKINS_PASS="${JENKINS_PASS:-${INIT_PASS:-admin}}"

echo "=== Jenkins Setup ==="

# 1. Wait for Jenkins
bash "$SCRIPT_DIR/wait-for-service.sh" "$JENKINS_URL/login" 300 "Jenkins"

# 2. Complete setup wizard (required before API access)
echo "🔧 Completing setup wizard..."
WIZARD_CRUMB_RESPONSE=$(curl -sf -c /tmp/jenkins-setup-cookies -u "$JENKINS_USER:$JENKINS_PASS" \
    "$JENKINS_URL/crumbIssuer/api/json" 2>/dev/null || echo "")
if [ -n "$WIZARD_CRUMB_RESPONSE" ]; then
    W_FIELD=$(echo "$WIZARD_CRUMB_RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin)['crumbRequestField'])")
    W_VALUE=$(echo "$WIZARD_CRUMB_RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin)['crumb'])")
    curl -sf -o /dev/null -b /tmp/jenkins-setup-cookies -u "$JENKINS_USER:$JENKINS_PASS" \
        -H "$W_FIELD: $W_VALUE" \
        -X POST "$JENKINS_URL/setupWizard/completeInstall" 2>/dev/null || true
    echo "  ✅ Setup wizard completed"
    sleep 2
else
    echo "  ⚠️  Could not complete setup wizard"
fi

# 3. Get crumb
echo "🔑 Getting CSRF crumb..."
CRUMB_RESPONSE=$(curl -sf -c /tmp/jenkins-cookies -u "$JENKINS_USER:$JENKINS_PASS" \
    "$JENKINS_URL/crumbIssuer/api/json" 2>/dev/null || echo "")

if [ -n "$CRUMB_RESPONSE" ]; then
    CRUMB_FIELD=$(echo "$CRUMB_RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin)['crumbRequestField'])")
    CRUMB_VALUE=$(echo "$CRUMB_RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin)['crumb'])")
    CRUMB_HEADER="$CRUMB_FIELD: $CRUMB_VALUE"
    echo "  ✅ Crumb obtained"
else
    CRUMB_HEADER=""
    echo "  ⚠️  No crumb (CSRF may be disabled)"
fi

# --- Helper: create or update Jenkins job ---
# Jenkins REST API가 UTF-8 한글을 ISO-8859-1로 변환하는 문제가 있어,
# docker exec으로 config.xml을 직접 쓴 뒤 Jenkins를 리로드하는 방식을 사용한다.
create_job() {
    local job_name=$1
    local config_file=$2

    echo "  📋 Creating/Updating job: $job_name"

    # Job 디렉토리 생성 (없으면)
    docker exec playground-jenkins mkdir -p "/var/jenkins_home/jobs/$job_name"

    # config.xml을 컨테이너에 직접 복사 (UTF-8 인코딩 보존)
    docker cp "$config_file" "playground-jenkins:/var/jenkins_home/jobs/$job_name/config.xml"

    echo "  ✅ Wrote config for $job_name"
}

# 4. Create jobs using temp XML files (avoids shell escaping issues)
echo ""
echo "📦 Creating Jenkins Pipeline jobs..."

# --- Build Pipeline ---
cat > /tmp/jenkins-build-config.xml << 'JOBXML'
<?xml version="1.0" encoding="UTF-8"?>
<flow-definition plugin="workflow-job">
  <description>Playground build pipeline (test/PoC) - 이벤트 기반 아키텍처 패턴 검증용</description>
  <definition class="org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition" plugin="workflow-cps">
    <script>
pipeline {
    agent any
    parameters {
        string(name: 'EXECUTION_ID', defaultValue: '', description: 'Pipeline execution ID')
        string(name: 'STEP_ORDER', defaultValue: '1', description: 'Pipeline step order')
        string(name: 'GIT_URL', defaultValue: '', description: 'Git repository URL')
        string(name: 'BRANCH', defaultValue: 'main', description: 'Git branch')
    }
    stages {
        stage('Init') {
            steps {
                echo '============================================'
                echo '  [TEST PIPELINE] Redpanda Playground Build'
                echo '============================================'
                echo "Execution ID : ${params.EXECUTION_ID}"
                echo "Step Order   : ${params.STEP_ORDER}"
                echo "GIT_URL      : ${params.GIT_URL ?: '(simulation)'}"
                echo "BRANCH       : ${params.BRANCH}"
                echo "Build Number : ${env.BUILD_NUMBER}"
                echo '--------------------------------------------'
            }
        }
        stage('Git Clone') {
            steps {
                script {
                    if (params.GIT_URL) {
                        echo "[REAL] Git Clone: ${params.GIT_URL} (branch: ${params.BRANCH})"
                        sh 'rm -rf project'
                        sh "git clone -b ${params.BRANCH} ${params.GIT_URL} project"
                        sh 'ls -la project/'
                    } else {
                        echo '[SIM] Git Clone 시뮬레이션'
                        sleep 2
                    }
                }
            }
        }
        stage('Build') {
            steps {
                script {
                    if (params.GIT_URL) {
                        echo '[REAL] 프로젝트 파일 분석'
                        sh 'find project/ -type f | head -20'
                        sh 'find project/ -type f -name "*.java" -o -name "*.md" | head -10 | xargs wc -l 2>/dev/null || echo "Source analysis complete"'
                    } else {
                        echo '[SIM] Build 시뮬레이션'
                        sleep 3
                    }
                }
            }
        }
        stage('Test') {
            steps {
                script {
                    if (params.GIT_URL) {
                        echo '[REAL] 테스트 (소스 구조 검증)'
                        sh 'find project/ -type f | wc -l'
                    } else {
                        echo '[SIM] Test 시뮬레이션'
                        sleep 2
                    }
                }
            }
        }
    }
    post {
        always {
            script {
                echo '============================================'
                echo "  [TEST PIPELINE] 빌드 종료: ${currentBuild.result ?: 'SUCCESS'}"
                echo '============================================'
                if (params.EXECUTION_ID) {
                    def result = currentBuild.result ?: 'SUCCESS'
                    def payload = """{"executionId":"${params.EXECUTION_ID}","stepOrder":${params.STEP_ORDER},"jobName":"${env.JOB_NAME}","buildNumber":${env.BUILD_NUMBER},"result":"${result}","duration":${currentBuild.duration},"url":"${env.BUILD_URL}","buildLog":"Jenkins build #${env.BUILD_NUMBER} ${result} (checkout->build->test)"}"""
                    sh "curl -sf -X POST http://playground-connect:4195/jenkins-webhook/webhook/jenkins -H 'Content-Type: application/json' -d '${payload}' || echo 'Webhook failed (non-fatal)'"
                }
            }
        }
    }
}
    </script>
    <sandbox>true</sandbox>
  </definition>
</flow-definition>
JOBXML

# --- Deploy Pipeline ---
cat > /tmp/jenkins-deploy-config.xml << 'JOBXML'
<?xml version="1.0" encoding="UTF-8"?>
<flow-definition plugin="workflow-job">
  <description>Playground deploy pipeline (test/PoC) - Break-and-Resume 및 SAGA 보상 검증용</description>
  <definition class="org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition" plugin="workflow-cps">
    <script>
pipeline {
    agent any
    parameters {
        string(name: 'EXECUTION_ID', defaultValue: '', description: 'Pipeline execution ID')
        string(name: 'STEP_ORDER', defaultValue: '1', description: 'Pipeline step order')
        string(name: 'DEPLOY_TARGET', defaultValue: '', description: 'Deploy target info')
    }
    stages {
        stage('Init') {
            steps {
                echo '============================================'
                echo '  [TEST PIPELINE] Redpanda Playground Deploy'
                echo '============================================'
                echo "Execution ID  : ${params.EXECUTION_ID}"
                echo "Step Order    : ${params.STEP_ORDER}"
                echo "Deploy Target : ${params.DEPLOY_TARGET ?: '(unknown)'}"
                echo "Build Number  : ${env.BUILD_NUMBER}"
                echo '--------------------------------------------'
            }
        }
        stage('Pre-Deploy Check') {
            steps {
                echo "[CD] 배포 대상: ${params.DEPLOY_TARGET ?: 'unknown'}"
                echo '[CD] Pre-Deploy Check 완료'
            }
        }
        stage('Deploy') {
            steps {
                echo '============================================'
                echo '  배포 대상 파일/아티팩트:'
                echo "  ${params.DEPLOY_TARGET ?: 'N/A'}"
                echo '============================================'
                echo '[CD] 실제 배포는 수행하지 않음 (PoC)'
            }
        }
        stage('Verify') {
            steps {
                echo '[CD] 배포 검증 완료 (시뮬레이션)'
            }
        }
    }
    post {
        always {
            script {
                echo '============================================'
                echo "  [TEST PIPELINE] 배포 종료: ${currentBuild.result ?: 'SUCCESS'}"
                echo '============================================'
                if (params.EXECUTION_ID) {
                    def result = currentBuild.result ?: 'SUCCESS'
                    def payload = """{"executionId":"${params.EXECUTION_ID}","stepOrder":${params.STEP_ORDER},"jobName":"${env.JOB_NAME}","buildNumber":${env.BUILD_NUMBER},"result":"${result}","duration":${currentBuild.duration},"url":"${env.BUILD_URL}","buildLog":"Jenkins deploy #${env.BUILD_NUMBER} ${result} (check->stop->deploy->verify)"}"""
                    sh "curl -sf -X POST http://playground-connect:4195/jenkins-webhook/webhook/jenkins -H 'Content-Type: application/json' -d '${payload}' || echo 'Webhook failed (non-fatal)'"
                }
            }
        }
    }
}
    </script>
    <sandbox>true</sandbox>
  </definition>
</flow-definition>
JOBXML

create_job "playground-build" "/tmp/jenkins-build-config.xml"
create_job "playground-deploy" "/tmp/jenkins-deploy-config.xml"

rm -f /tmp/jenkins-build-config.xml /tmp/jenkins-deploy-config.xml

# 5. Reload Jenkins to pick up new job configs
echo ""
echo "🔄 Reloading Jenkins configuration..."
if [ -n "$CRUMB_HEADER" ]; then
    curl -sf -o /dev/null -b /tmp/jenkins-cookies -u "$JENKINS_USER:$JENKINS_PASS" \
        -H "$CRUMB_HEADER" \
        -X POST "$JENKINS_URL/reload" 2>/dev/null || true
    echo "  ✅ Jenkins reload requested"
    # Wait for Jenkins to come back after reload
    sleep 5
    bash "$SCRIPT_DIR/wait-for-service.sh" "$JENKINS_URL/login" 120 "Jenkins (reload)"
else
    echo "  ⚠️  No crumb available, restarting container instead..."
    docker restart playground-jenkins
    sleep 5
    bash "$SCRIPT_DIR/wait-for-service.sh" "$JENKINS_URL/login" 120 "Jenkins (restart)"
fi

echo ""
echo "=== Jenkins Setup Complete ==="
echo "  Jenkins URL: $JENKINS_URL"
echo "  Credentials: $JENKINS_USER / $JENKINS_PASS"
echo "  Jobs: playground-build, playground-deploy"
echo "  Webhook: Jobs send callback to playground-connect:4195/jenkins-webhook/webhook/jenkins"
