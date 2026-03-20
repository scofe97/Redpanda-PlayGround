FROM jenkins/jenkins:lts-jdk17

# UTF-8 로케일 설정 (한글 깨짐 방지)
ENV LANG=C.UTF-8
ENV LC_ALL=C.UTF-8
ENV JAVA_OPTS="-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -Djenkins.install.runSetupWizard=false"

# Pipeline + CasC 플러그인 (PoC)
RUN jenkins-plugin-cli --plugins \
    configuration-as-code \
    cloudbees-folder \
    job-dsl \
    workflow-job \
    workflow-cps \
    workflow-basic-steps \
    workflow-durable-task-step \
    workflow-step-api \
    workflow-aggregator \
    pipeline-model-definition \
    pipeline-stage-step \
    pipeline-input-step

# rpk CLI (Kafka 직접 발행용, ~30MB 단일 바이너리)
USER root
RUN curl -sL https://github.com/redpanda-data/redpanda/releases/latest/download/rpk-linux-amd64.zip \
    -o /tmp/rpk.zip \
    && unzip /tmp/rpk.zip -d /usr/local/bin/ \
    && rm /tmp/rpk.zip \
    && chmod +x /usr/local/bin/rpk
USER jenkins

# CSRF 비활성화 (PoC - API 호출 시 crumb 불필요)
COPY disable-csrf.groovy /usr/share/jenkins/ref/init.groovy.d/

# 전역 설정 추가
COPY webhook-listener.groovy /usr/share/jenkins/ref/init.groovy.d/
