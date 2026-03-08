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
create_job() {
    local job_name=$1
    local config_file=$2

    echo "  📋 Creating/Updating job: $job_name"

    # Try create first (use @file to preserve newlines in XML)
    local http_code
    http_code=$(curl -s -o /dev/null -w "%{http_code}" -b /tmp/jenkins-cookies -u "$JENKINS_USER:$JENKINS_PASS" \
        ${CRUMB_HEADER:+-H "$CRUMB_HEADER"} \
        -H "Content-Type: application/xml" \
        --data-binary @"$config_file" \
        "$JENKINS_URL/createItem?name=$job_name" 2>/dev/null)

    if [ "$http_code" = "200" ] || [ "$http_code" = "302" ]; then
        echo "  ✅ Created $job_name"
    elif [ "$http_code" = "400" ]; then
        # Job already exists → update config
        echo "  🔄 $job_name already exists, updating..."
        local update_code
        update_code=$(curl -s -o /dev/null -w "%{http_code}" -b /tmp/jenkins-cookies -u "$JENKINS_USER:$JENKINS_PASS" \
            ${CRUMB_HEADER:+-H "$CRUMB_HEADER"} \
            -H "Content-Type: application/xml" \
            -X POST --data-binary @"$config_file" \
            "$JENKINS_URL/job/$job_name/config.xml" 2>/dev/null)
        if [ "$update_code" = "200" ] || [ "$update_code" = "302" ]; then
            echo "  ✅ Updated $job_name"
        else
            echo "  ⚠️  $job_name update returned HTTP $update_code"
        fi
    else
        echo "  ⚠️  $job_name creation returned HTTP $http_code"
    fi
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
    }
    stages {
        stage('Init') {
            steps {
                echo '============================================'
                echo '  [TEST PIPELINE] Redpanda Playground Build'
                echo '============================================'
                echo "Execution ID : ${params.EXECUTION_ID}"
                echo "Step Order   : ${params.STEP_ORDER}"
                echo "Build Number : ${env.BUILD_NUMBER}"
                echo '--------------------------------------------'
            }
        }
        stage('Git Checkout') {
            steps {
                echo '[STEP 1/3] Git Checkout - 소스 코드 체크아웃 시뮬레이션'
                echo '  > 실제: git clone + branch 전환'
                echo '  > 테스트: 2초 대기'
                sleep 2
                echo '  > Checkout 완료'
            }
        }
        stage('Build') {
            steps {
                echo '[STEP 2/3] Build - 프로젝트 컴파일/패키징 시뮬레이션'
                echo '  > 실제: gradlew build / mvn package'
                echo '  > 테스트: 3초 대기'
                sleep 3
                echo '  > Build 완료 (42 classes compiled)'
            }
        }
        stage('Test') {
            steps {
                echo '[STEP 3/3] Test - 단위 테스트 시뮬레이션'
                echo '  > 실제: gradlew test + 리포트 생성'
                echo '  > 테스트: 2초 대기'
                sleep 2
                echo '  > Test 완료 (15 passed, 0 failed)'
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
                    sh "curl -sf -X POST http://playground-connect:4197/webhook/jenkins -H 'Content-Type: application/json' -d '${payload}' || echo 'Webhook failed (non-fatal)'"
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
    }
    stages {
        stage('Init') {
            steps {
                echo '============================================'
                echo '  [TEST PIPELINE] Redpanda Playground Deploy'
                echo '============================================'
                echo "Execution ID : ${params.EXECUTION_ID}"
                echo "Step Order   : ${params.STEP_ORDER}"
                echo "Build Number : ${env.BUILD_NUMBER}"
                echo '--------------------------------------------'
            }
        }
        stage('Pre-Deploy Check') {
            steps {
                echo '[STEP 1/4] Pre-Deploy Check - 배포 전 상태 확인 시뮬레이션'
                echo '  > 실제: 타겟 서버 헬스체크 + 리소스 확인'
                echo '  > 테스트: 1초 대기'
                sleep 1
                echo '  > Pre-Deploy Check 완료 (target healthy)'
            }
        }
        stage('Stop Service') {
            steps {
                echo '[STEP 2/4] Stop Service - 기존 서비스 중지 시뮬레이션'
                echo '  > 실제: docker stop + graceful shutdown 대기'
                echo '  > 테스트: 2초 대기'
                sleep 2
                echo '  > Stop Service 완료'
            }
        }
        stage('Deploy') {
            steps {
                echo '[STEP 3/4] Deploy - 아티팩트 배포 시뮬레이션'
                echo '  > 실제: docker pull + run / SCP + 설정 교체'
                echo '  > 테스트: 3초 대기'
                sleep 3
                echo '  > Deploy 완료'
            }
        }
        stage('Verify') {
            steps {
                echo '[STEP 4/4] Verify - 배포 후 검증 시뮬레이션'
                echo '  > 실제: health check 폴링 + smoke test'
                echo '  > 테스트: 2초 대기'
                sleep 2
                echo '  > Verify 완료 (HTTP 200 OK)'
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
                    sh "curl -sf -X POST http://playground-connect:4197/webhook/jenkins -H 'Content-Type: application/json' -d '${payload}' || echo 'Webhook failed (non-fatal)'"
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

echo ""
echo "=== Jenkins Setup Complete ==="
echo "  Jenkins URL: $JENKINS_URL"
echo "  Credentials: $JENKINS_USER / $JENKINS_PASS"
echo "  Jobs: playground-build, playground-deploy"
echo "  Webhook: Jobs send callback to playground-connect:4197/webhook/jenkins"
