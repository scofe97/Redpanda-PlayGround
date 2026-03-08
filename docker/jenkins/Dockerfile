FROM jenkins/jenkins:lts-jdk17

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
