package com.study.playground.pipeline.engine;

import com.study.playground.common.tracing.TraceContextUtil;
import com.study.playground.pipeline.domain.*;
import com.study.playground.pipeline.engine.step.*;
import com.study.playground.pipeline.event.PipelineEventProducer;
import com.study.playground.pipeline.mapper.PipelineExecutionMapper;
import com.study.playground.pipeline.mapper.PipelineStepMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 파이프라인 엔진 — 스텝 순차 실행과 SAGA 보상을 담당한다.
 *
 * <p>각 스텝은 동기적으로 순서대로 실행된다. 실패 시 {@link SagaCompensator}를 통해
 * 이미 완료된 스텝을 역순으로 보상(롤백)한다. 이렇게 설계한 이유는 분산 트랜잭션 없이도
 * 부분 실패를 원자적으로 처리할 수 있기 때문이다.
 *
 * <p>Jenkins, Deploy처럼 외부 시스템 완료를 기다려야 하는 스텝은 Break-and-Resume 패턴을
 * 사용한다. 스텝이 {@code waitingForWebhook=true}를 설정하면 현재 스레드를 즉시 해제하고,
 * 외부 콜백(webhook) 수신 시 {@link #resumeAfterWebhook}에서 실행을 재개한다.
 * 스레드를 블로킹하지 않으므로 긴 빌드/배포 시간에도 서버 자원을 낭비하지 않는다.
 */
@Slf4j
@Component
public class PipelineEngine {

    private final Map<PipelineStepType, PipelineStepExecutor> stepExecutors;
    private final PipelineExecutionMapper executionMapper;
    private final PipelineStepMapper stepMapper;
    private final PipelineEventProducer eventProducer;
    private final SagaCompensator sagaCompensator;

    /**
     * 스텝 타입별 실행기를 Map으로 관리하여 if-else/switch 없이 디스패치한다.
     * GIT_CLONE과 BUILD가 동일 실행기({@link JenkinsCloneAndBuildStep})를 공유하는 이유는
     * Jenkins 파이프라인이 clone과 build를 하나의 작업으로 처리하기 때문이다.
     */
    public PipelineEngine(
            JenkinsCloneAndBuildStep gitCloneAndBuild,
            NexusDownloadStep nexusDownload,
            RegistryImagePullStep imagePull,
            JenkinsDeployStep deploy,
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

    /**
     * 파이프라인 실행을 시작한다.
     * 상태를 RUNNING으로 전환한 뒤 첫 번째 스텝(index=0)부터 실행을 위임한다.
     *
     * @param execution 실행할 파이프라인 실행 정보
     */
    public void execute(PipelineExecution execution) {
        log.info("Pipeline execution started: {}", execution.getId());
        executionMapper.updateStatus(
                execution.getId(),
                PipelineStatus.RUNNING.name(),
                null,
                null);

        executeFrom(execution, 0, System.currentTimeMillis());
    }

    /**
     * 지정된 인덱스부터 파이프라인 스텝을 순차 실행한다.
     * 스텝이 waitingForWebhook=true를 설정하면 루프를 중단하고 스레드를 해제한다.
     *
     * <p>매 진입 시 DB에서 스텝 목록을 다시 조회하는 이유는 webhook 재개 시점에
     * 다른 스텝의 상태가 외부에서 변경되었을 수 있기 때문이다.
     *
     * @param execution  파이프라인 실행 정보
     * @param fromIndex  실행을 시작할 스텝 인덱스 (0-based)
     * @param startTime  전체 실행 시작 시각 (밀리초, 소요 시간 계산용)
     */
    private void executeFrom(PipelineExecution execution, int fromIndex, long startTime) {
        // 매 진입 시 DB에서 스텝 목록을 다시 조회하여 최신 스냅샷으로 작업한다
        execution.setSteps(stepMapper.findByExecutionId(execution.getId()));
        List<PipelineStep> steps = execution.getSteps();

        for (int i = fromIndex; i < steps.size(); i++) {
            // i번째 스텝 상태 조회 및 진행중 변경
            PipelineStep step = steps.get(i);
            updateStepStatus(execution, step, StepStatus.RUNNING, null);

            try {
                // 실행 step 조회 및 실행한다.
                var executor = stepExecutors.get(step.getStepType());
                if (executor == null) {
                    throw new IllegalStateException("No executor for step type: " + step.getStepType());
                }
                executor.execute(execution, step);

                // Break-and-Resume: webhook 대기 상태면 스레드를 해제하고 루프 중단
                if (step.isWaitingForWebhook()) {
                    updateStepStatus(execution, step, StepStatus.WAITING_WEBHOOK,
                            "Waiting for Jenkins webhook callback...");
                    log.info("Step {} waiting for webhook - thread released (execution={})",
                            step.getStepName(), execution.getId());
                    return; // 스레드 반환 — webhook 도착 시 resumeAfterWebhook()에서 재개
                }

                updateStepStatus(execution, step, StepStatus.SUCCESS, step.getLog());
            } catch (Exception e) {
                log.error("Step failed: {}", step.getStepName(), e);
                updateStepStatus(execution, step, StepStatus.FAILED, e.getMessage());

                long duration = System.currentTimeMillis() - startTime;
                failExecution(execution, step.getStepOrder(), duration, e.getMessage());
                return;
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        completeExecution(execution, duration);
    }

    /**
     * webhook 콜백 수신 후 파이프라인 실행을 재개한다.
     * Jenkins 빌드 완료 시 WebhookEventConsumer가 호출한다.
     *
     * <p>CAS(Compare-And-Swap) 방식으로 상태를 갱신하는 이유는 타임아웃 체커
     * ({@link WebhookTimeoutChecker})가 동시에 FAILED로 전환할 수 있기 때문이다.
     * {@code updateStatusIfCurrent}가 0을 반환하면 이미 다른 경쟁자가 상태를 변경한
     * 것이므로 중복 처리를 막기 위해 즉시 반환한다.
     *
     * @param executionId 재개할 파이프라인 실행 ID
     * @param stepOrder   완료된 스텝의 순서 (1-based)
     * @param result      Jenkins 빌드 결과 ("SUCCESS" 또는 그 외 실패 값)
     * @param buildLog    Jenkins 빌드 로그 (null 허용)
     */
    public void resumeAfterWebhook(
            UUID executionId,
            int stepOrder,
            String result,
            String buildLog) {
        var execution = executionMapper.findById(executionId);
        if (execution == null) {
            log.warn("resumeAfterWebhook: execution not found: {}", executionId);
            return;
        }

        var step = stepMapper.findByExecutionIdAndStepOrder(executionId, stepOrder);
        if (step == null) {
            log.warn("resumeAfterWebhook: step not found: execution={}, stepOrder={}", executionId, stepOrder);
            return;
        }

        // 원래 trace에 연결하여 webhook resume 스팬을 생성한다
        TraceContextUtil.executeWithRestoredTrace(
                execution.getTraceParent()
                , "PipelineEngine.resumeAfterWebhook"
                , Map.of("pipeline.execution.id", executionId.toString()
                        , "pipeline.step.order", String.valueOf(stepOrder))
                , () -> doResumeAfterWebhook(execution, step, executionId, stepOrder, result, buildLog)
        );
    }

    private void doResumeAfterWebhook(PipelineExecution execution, PipelineStep step
            , UUID executionId, int stepOrder, String result, String buildLog) {
        // CAS: WAITING_WEBHOOK → SUCCESS/FAILED (타임아웃 체커와의 경쟁 조건 방지)
        boolean success = "SUCCESS".equalsIgnoreCase(result);
        var targetStatus = success ? StepStatus.SUCCESS.name() : StepStatus.FAILED.name();
        var logMsg = success ? buildLog : (buildLog != null ? buildLog : "Jenkins build failed: " + result);

        int affected = stepMapper.updateStatusIfCurrent(
                step.getId(),
                StepStatus.WAITING_WEBHOOK.name(),
                targetStatus,
                logMsg,
                LocalDateTime.now());

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

            long duration = calculateDurationMs(execution);
            failExecution(execution, stepOrder, duration, logMsg);
            log.info("Webhook resume: step {} FAILED (execution={})", step.getStepName(), executionId);
        }
    }

    // ── private helpers ──────────────────────────────────────────────

    private void updateStepStatus(PipelineExecution execution, PipelineStep step,
                                  StepStatus status, String logMessage) {
        stepMapper.updateStatus(step.getId(), status.name(), logMessage, LocalDateTime.now());
        eventProducer.publishStepChanged(execution, step, status);
    }

    private void failExecution(PipelineExecution execution, int failedStepOrder,
                               long durationMs, String errorMessage) {
        sagaCompensator.compensate(execution, failedStepOrder, stepExecutors);
        executionMapper.updateStatus(
                execution.getId(),
                PipelineStatus.FAILED.name(),
                LocalDateTime.now(),
                errorMessage);
        eventProducer.publishExecutionCompleted(
                execution,
                com.study.playground.avro.common.PipelineStatus.FAILED,
                durationMs,
                errorMessage);
    }

    private void completeExecution(PipelineExecution execution, long durationMs) {
        executionMapper.updateStatus(
                execution.getId(),
                PipelineStatus.SUCCESS.name(),
                LocalDateTime.now(),
                null);

        eventProducer.publishExecutionCompleted(
                execution,
                com.study.playground.avro.common.PipelineStatus.SUCCESS,
                durationMs,
                null);
        log.info("Pipeline execution completed: {} in {}ms", execution.getId(), durationMs);
    }

    private long calculateDurationMs(PipelineExecution execution) {
        return execution.getStartedAt() != null
                ? Duration.between(execution.getStartedAt(), LocalDateTime.now()).toMillis()
                : 0;
    }
}
