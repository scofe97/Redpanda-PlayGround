FROM jenkins/jenkins:lts-jdk17

# UTF-8 로케일 설정 (한글 깨짐 방지)
ENV LANG=C.UTF-8
ENV LC_ALL=C.UTF-8
ENV JAVA_OPTS="-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8"

# Pipeline 플러그인 및 의존성 사전 설치
RUN jenkins-plugin-cli --plugins \
    workflow-job \
    workflow-cps \
    workflow-basic-steps \
    workflow-durable-task-step \
    workflow-step-api \
    workflow-aggregator \
    pipeline-model-definition \
    pipeline-stage-step \
    pipeline-input-step
