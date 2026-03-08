FROM jenkins/jenkins:lts-jdk17

# UTF-8 로케일 설정 (한글 깨짐 방지)
ENV LANG=C.UTF-8
ENV LC_ALL=C.UTF-8
ENV JAVA_OPTS="-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -Djenkins.install.runSetupWizard=false -Dpermissive-script-security.enabled=true"

# Pipeline + CasC + 보안 비활성화 플러그인 (PoC)
RUN jenkins-plugin-cli --plugins \
    configuration-as-code \
    job-dsl \
    permissive-script-security \
    workflow-job \
    workflow-cps \
    workflow-basic-steps \
    workflow-durable-task-step \
    workflow-step-api \
    workflow-aggregator \
    pipeline-model-definition \
    pipeline-stage-step \
    pipeline-input-step

# CSRF 비활성화 (PoC - API 호출 시 crumb 불필요)
COPY disable-csrf.groovy /usr/share/jenkins/ref/init.groovy.d/
