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
     * Executes pipeline steps starting from the given index.
     * If a step sets waitingForWebhook=true, the loop breaks and the thread is released.
     */
    private void executeFrom(PipelineExecution execution, int fromIndex, long startTime) {
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

                // Break-and-Resume: webhook 대기 시 스레드 해제
                if (step.isWaitingForWebhook()) {
                    stepMapper.updateStatus(step.getId(), StepStatus.WAITING_WEBHOOK.name(),
                            "Waiting for Jenkins webhook callback...", LocalDateTime.now());
                    eventProducer.publishStepChanged(execution, step, StepStatus.WAITING_WEBHOOK);
                    log.info("Step {} waiting for webhook - thread released (execution={})",
                            step.getStepName(), execution.getId());
                    return; // 스레드 해제 — webhook 도착 시 resumeAfterWebhook()으로 재개
                }

                stepMapper.updateStatus(step.getId(), StepStatus.SUCCESS.name(),
                        step.getLog(), LocalDateTime.now());
                eventProducer.publishStepChanged(execution, step, StepStatus.SUCCESS);
            } catch (Exception e) {
                log.error("Step failed: {}", step.getStepName(), e);
                stepMapper.updateStatus(step.getId(), StepStatus.FAILED.name(),
                        e.getMessage(), LocalDateTime.now());
                eventProducer.publishStepChanged(execution, step, StepStatus.FAILED);

                // SAGA: compensate completed steps in reverse order
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
     * Resumes pipeline execution after a webhook callback.
     * Called by WebhookEventConsumer when Jenkins job completes.
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

        // CAS: WAITING_WEBHOOK → SUCCESS/FAILED (타임아웃과의 경쟁 조건 방지)
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

        // Reload steps for the execution
        execution.setSteps(stepMapper.findByExecutionId(executionId));

        if (success) {
            step.setLog(buildLog);
            eventProducer.publishStepChanged(execution, step, StepStatus.SUCCESS);
            log.info("Webhook resume: step {} SUCCESS (execution={})", step.getStepName(), executionId);

            // Continue from next step (stepOrder is 1-based, fromIndex is 0-based)
            executeFrom(execution, stepOrder, System.currentTimeMillis());
        } else {
            eventProducer.publishStepChanged(execution, step, StepStatus.FAILED);

            // SAGA: compensate completed steps in reverse order
            sagaCompensator.compensate(execution, stepOrder, stepExecutors);

            executionMapper.updateStatus(executionId, PipelineStatus.FAILED.name(),
                    LocalDateTime.now(), logMsg);
            eventProducer.publishExecutionCompleted(execution,
                    com.study.playground.avro.common.PipelineStatus.FAILED, 0, logMsg);
            log.info("Webhook resume: step {} FAILED (execution={})", step.getStepName(), executionId);
        }
    }
}
