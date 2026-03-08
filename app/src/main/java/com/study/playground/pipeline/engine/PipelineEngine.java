package com.study.playground.pipeline.engine;

import com.study.playground.pipeline.domain.*;
import com.study.playground.pipeline.engine.step.*;
import com.study.playground.pipeline.event.PipelineEventProducer;
import com.study.playground.pipeline.mapper.PipelineExecutionMapper;
import com.study.playground.pipeline.mapper.PipelineStepMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
public class PipelineEngine {

    private final Map<PipelineStepType, PipelineStepExecutor> stepExecutors;
    private final PipelineExecutionMapper executionMapper;
    private final PipelineStepMapper stepMapper;
    private final PipelineEventProducer eventProducer;
    private final SagaCompensator sagaCompensator;

    public PipelineEngine(JenkinsCloneAndBuildStep gitCloneAndBuild,
                          NexusDownloadStep nexusDownload,
                          RegistryImagePullStep imagePull,
                          RealDeployStep deploy,
                          PipelineExecutionMapper executionMapper,
                          PipelineStepMapper stepMapper,
                          PipelineEventProducer eventProducer,
                          SagaCompensator sagaCompensator) {
        this.stepExecutors = Map.of(
                PipelineStepType.GIT_CLONE, gitCloneAndBuild,
                PipelineStepType.BUILD, gitCloneAndBuild,
                PipelineStepType.ARTIFACT_DOWNLOAD, nexusDownload,
                PipelineStepType.IMAGE_PULL, imagePull,
                PipelineStepType.DEPLOY, deploy
        );
        this.executionMapper = executionMapper;
        this.stepMapper = stepMapper;
        this.eventProducer = eventProducer;
        this.sagaCompensator = sagaCompensator;
    }

    public void execute(PipelineExecution execution) {
        log.info("Pipeline execution started: {}", execution.getId());
        executionMapper.updateStatus(execution.getId(), PipelineStatus.RUNNING.name(),
                null, null);

        executeFrom(execution, 0, System.currentTimeMillis());
    }

    /**
     * 지정된 인덱스부터 파이프라인 스텝을 순차 실행한다.
     * 스텝이 waitingForWebhook=true를 설정하면 루프를 중단하고 스레드를 해제한다.
     */
    private void executeFrom(PipelineExecution execution, int fromIndex, long startTime) {
        // 매 진입 시 DB에서 스텝 목록을 다시 조회하여 최신 스냅샷으로 작업한다
        execution.setSteps(stepMapper.findByExecutionId(execution.getId()));
        List<PipelineStep> steps = execution.getSteps();

        for (int i = fromIndex; i < steps.size(); i++) {
            PipelineStep step = steps.get(i);
            stepMapper.updateStatus(step.getId(), StepStatus.RUNNING.name(), null, null);
            eventProducer.publishStepChanged(execution, step, StepStatus.RUNNING);

            try {
                PipelineStepExecutor executor = stepExecutors.get(step.getStepType());
                if (executor == null) {
                    throw new IllegalStateException("No executor for step type: " + step.getStepType());
                }
                executor.execute(execution, step);

                // Break-and-Resume: webhook 대기 상태면 스레드를 해제하고 루프 중단
                if (step.isWaitingForWebhook()) {
                    stepMapper.updateStatus(step.getId(), StepStatus.WAITING_WEBHOOK.name(),
                            "Waiting for Jenkins webhook callback...", LocalDateTime.now());
                    eventProducer.publishStepChanged(execution, step, StepStatus.WAITING_WEBHOOK);
                    log.info("Step {} waiting for webhook - thread released (execution={})",
                            step.getStepName(), execution.getId());
                    return; // 스레드 반환 — webhook 도착 시 resumeAfterWebhook()에서 재개
                }

                stepMapper.updateStatus(step.getId(), StepStatus.SUCCESS.name(),
                        step.getLog(), LocalDateTime.now());
                eventProducer.publishStepChanged(execution, step, StepStatus.SUCCESS);
            } catch (Exception e) {
                log.error("Step failed: {}", step.getStepName(), e);
                stepMapper.updateStatus(step.getId(), StepStatus.FAILED.name(),
                        e.getMessage(), LocalDateTime.now());
                eventProducer.publishStepChanged(execution, step, StepStatus.FAILED);

                // SAGA: 완료된 스텝을 역순으로 보상 처리
                sagaCompensator.compensate(execution, step.getStepOrder(), stepExecutors);

                long duration = System.currentTimeMillis() - startTime;
                executionMapper.updateStatus(execution.getId(), PipelineStatus.FAILED.name(),
                        LocalDateTime.now(), e.getMessage());
                eventProducer.publishExecutionCompleted(execution,
                        com.study.playground.avro.common.PipelineStatus.FAILED, duration, e.getMessage());
                return;
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        executionMapper.updateStatus(execution.getId(), PipelineStatus.SUCCESS.name(),
                LocalDateTime.now(), null);
        eventProducer.publishExecutionCompleted(execution,
                com.study.playground.avro.common.PipelineStatus.SUCCESS, duration, null);
        log.info("Pipeline execution completed: {} in {}ms", execution.getId(), duration);
    }

    /**
     * webhook 콜백 수신 후 파이프라인 실행을 재개한다.
     * Jenkins 빌드 완료 시 WebhookEventConsumer가 호출한다.
     */
    public void resumeAfterWebhook(UUID executionId, int stepOrder, String result, String buildLog) {
        PipelineExecution execution = executionMapper.findById(executionId);
        if (execution == null) {
            log.warn("resumeAfterWebhook: execution not found: {}", executionId);
            return;
        }

        PipelineStep step = stepMapper.findByExecutionIdAndStepOrder(executionId, stepOrder);
        if (step == null) {
            log.warn("resumeAfterWebhook: step not found: execution={}, stepOrder={}", executionId, stepOrder);
            return;
        }

        // CAS: WAITING_WEBHOOK → SUCCESS/FAILED (타임아웃 체커와의 경쟁 조건 방지)
        boolean success = "SUCCESS".equalsIgnoreCase(result);
        String targetStatus = success ? StepStatus.SUCCESS.name() : StepStatus.FAILED.name();
        String logMsg = success ? buildLog : (buildLog != null ? buildLog : "Jenkins build failed: " + result);

        int affected = stepMapper.updateStatusIfCurrent(
                step.getId(), StepStatus.WAITING_WEBHOOK.name(), targetStatus, logMsg, LocalDateTime.now());

        if (affected == 0) {
            log.warn("resumeAfterWebhook: CAS failed - step already changed: execution={}, stepOrder={}, currentStatus={}",
                    executionId, stepOrder, step.getStatus());
            return;
        }

        // 실행의 스텝 목록을 DB에서 다시 로드
        execution.setSteps(stepMapper.findByExecutionId(executionId));

        if (success) {
            step.setLog(buildLog);
            eventProducer.publishStepChanged(execution, step, StepStatus.SUCCESS);
            log.info("Webhook resume: step {} SUCCESS (execution={})", step.getStepName(), executionId);

            // 다음 스텝부터 이어서 실행 (stepOrder는 1-based, fromIndex는 0-based)
            executeFrom(execution, stepOrder, System.currentTimeMillis());
        } else {
            eventProducer.publishStepChanged(execution, step, StepStatus.FAILED);

            // SAGA: 완료된 스텝을 역순으로 보상 처리
            sagaCompensator.compensate(execution, stepOrder, stepExecutors);

            long duration = execution.getStartedAt() != null
                    ? java.time.Duration.between(execution.getStartedAt(), java.time.LocalDateTime.now()).toMillis()
                    : 0;
            executionMapper.updateStatus(executionId, PipelineStatus.FAILED.name(),
                    LocalDateTime.now(), logMsg);
            eventProducer.publishExecutionCompleted(execution,
                    com.study.playground.avro.common.PipelineStatus.FAILED, duration, logMsg);
            log.info("Webhook resume: step {} FAILED (execution={})", step.getStepName(), executionId);
        }
    }
}
