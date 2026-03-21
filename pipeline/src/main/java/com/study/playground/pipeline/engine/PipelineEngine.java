package com.study.playground.pipeline.engine;

import com.study.playground.kafka.tracing.TraceContextUtil;
import com.study.playground.avro.common.AvroPipelineStatus;
import com.study.playground.pipeline.domain.*;
import com.study.playground.pipeline.engine.step.*;
import com.study.playground.pipeline.dag.engine.DagExecutionCoordinator;
import com.study.playground.pipeline.event.PipelineEventProducer;
import com.study.playground.pipeline.mapper.PipelineExecutionMapper;
import com.study.playground.pipeline.mapper.PipelineJobExecutionMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 파이프라인 엔진 — Job 순차 실행과 SAGA 보상을 담당한다.
 *
 * <p>각 Job은 동기적으로 순서대로 실행된다. 실패 시 {@link SagaCompensator}를 통해
 * 이미 완료된 Job을 역순으로 보상(롤백)한다. 이렇게 설계한 이유는 분산 트랜잭션 없이도
 * 부분 실패를 원자적으로 처리할 수 있기 때문이다.
 *
 * <p>Jenkins, Deploy처럼 외부 시스템 완료를 기다려야 하는 Job은 Break-and-Resume 패턴을
 * 사용한다. Job이 {@code waitingForWebhook=true}를 설정하면 현재 스레드를 즉시 해제하고,
 * 외부 콜백(webhook) 수신 시 {@link #resumeAfterWebhook}에서 실행을 재개한다.
 * 스레드를 블로킹하지 않으므로 긴 빌드/배포 시간에도 서버 자원을 낭비하지 않는다.
 */
@Slf4j
@Component
public class PipelineEngine {

    private final Map<PipelineJobType, PipelineJobExecutor> jobExecutors;
    private final PipelineExecutionMapper executionMapper;
    private final PipelineJobExecutionMapper jobExecutionMapper;
    private final PipelineEventProducer eventProducer;
    private final SagaCompensator sagaCompensator;
    private final DagExecutionCoordinator dagCoordinator;

    /**
     * Job 타입별 실행기를 Map으로 관리하여 if-else/switch 없이 디스패치한다.
     */
    public PipelineEngine(
            JenkinsCloneAndBuildStep gitCloneAndBuild,
            NexusDownloadStep nexusDownload,
            RegistryImagePullStep imagePull,
            JenkinsDeployStep deploy,
            PipelineExecutionMapper executionMapper,
            PipelineJobExecutionMapper jobExecutionMapper,
            PipelineEventProducer eventProducer,
            SagaCompensator sagaCompensator,
            DagExecutionCoordinator dagCoordinator) {
        this.jobExecutors = Map.of(
                PipelineJobType.BUILD, gitCloneAndBuild
                , PipelineJobType.ARTIFACT_DOWNLOAD, nexusDownload
                , PipelineJobType.IMAGE_PULL, imagePull
                , PipelineJobType.DEPLOY, deploy
        );
        this.executionMapper = executionMapper;
        this.jobExecutionMapper = jobExecutionMapper;
        this.eventProducer = eventProducer;
        this.sagaCompensator = sagaCompensator;
        this.dagCoordinator = dagCoordinator;
    }

    /**
     * 파이프라인 실행을 시작한다.
     * pipelineDefinitionId가 있으면 DAG 모드로, 없으면 기존 순차 모드로 실행한다.
     *
     * @param execution 실행할 파이프라인 실행 정보
     */
    public void execute(PipelineExecution execution) {
        log.info("Pipeline execution started: {}", execution.getId());

        if (execution.getPipelineDefinitionId() != null) {
            // DAG 모드: DagExecutionCoordinator에 위임
            dagCoordinator.startExecution(execution);
        } else {
            // 기존 순차 모드
            executionMapper.updateStatus(
                    execution.getId(),
                    PipelineStatus.RUNNING.name(),
                    null,
                    null);
            executeFrom(execution, 0, System.currentTimeMillis());
        }
    }

    /**
     * 지정된 인덱스부터 파이프라인 Job을 순차 실행한다.
     * Job이 waitingForWebhook=true를 설정하면 루프를 중단하고 스레드를 해제한다.
     *
     * <p>매 진입 시 DB에서 Job 목록을 다시 조회하는 이유는 webhook 재개 시점에
     * 다른 Job의 상태가 외부에서 변경되었을 수 있기 때문이다.
     *
     * @param execution  파이프라인 실행 정보
     * @param fromIndex  실행을 시작할 Job 인덱스 (0-based)
     * @param startTime  전체 실행 시작 시각 (밀리초, 소요 시간 계산용)
     */
    private void executeFrom(PipelineExecution execution, int fromIndex, long startTime) {
        // 매 진입 시 DB에서 Job 목록을 다시 조회하여 최신 스냅샷으로 작업한다
        execution.setJobExecutions(jobExecutionMapper.findByExecutionId(execution.getId()));
        List<PipelineJobExecution> jobExecutions = execution.getJobExecutions();

        // 사용자 파라미터 로드
        var userParams = execution.parameters();

        for (int i = fromIndex; i < jobExecutions.size(); i++) {
            // i번째 Job 상태 조회 및 진행중 변경
            PipelineJobExecution jobExecution = jobExecutions.get(i);
            if (!userParams.isEmpty()) {
                jobExecution.setUserParams(userParams);
            }
            updateJobExecutionStatus(execution, jobExecution, JobExecutionStatus.RUNNING, null);

            try {
                // 실행 Job 조회 및 실행한다.
                var executor = jobExecutors.get(jobExecution.getJobType());
                if (executor == null) {
                    throw new IllegalStateException("No executor for job type: " + jobExecution.getJobType());
                }
                executor.execute(execution, jobExecution);

                // Break-and-Resume: webhook 대기 상태면 스레드를 해제하고 루프 중단
                if (jobExecution.isWaitingForWebhook()) {
                    updateJobExecutionStatus(execution, jobExecution, JobExecutionStatus.WAITING_WEBHOOK,
                            "Waiting for Jenkins webhook callback...");
                    log.info("Job {} waiting for webhook - thread released (execution={})",
                            jobExecution.getJobName(), execution.getId());
                    return; // 스레드 반환 — webhook 도착 시 resumeAfterWebhook()에서 재개
                }

                updateJobExecutionStatus(execution, jobExecution, JobExecutionStatus.SUCCESS, jobExecution.getLog());
            } catch (Exception e) {
                log.error("Job failed: {}", jobExecution.getJobName(), e);
                updateJobExecutionStatus(execution, jobExecution, JobExecutionStatus.FAILED, e.getMessage());

                long duration = System.currentTimeMillis() - startTime;
                failExecution(execution, jobExecution.getJobOrder(), duration, e.getMessage());
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
     * @param jobOrder    완료된 Job의 순서 (1-based)
     * @param result      Jenkins 빌드 결과 ("SUCCESS" 또는 그 외 실패 값)
     * @param buildLog    Jenkins 빌드 로그 (null 허용)
     */
    public void resumeAfterWebhook(
            UUID executionId,
            int jobOrder,
            String result,
            String buildLog) {
        var execution = executionMapper.findById(executionId);
        if (execution == null) {
            log.warn("resumeAfterWebhook: execution not found: {}", executionId);
            return;
        }

        var jobExecution = jobExecutionMapper.findByExecutionIdAndJobOrder(executionId, jobOrder);
        if (jobExecution == null) {
            log.warn("resumeAfterWebhook: jobExecution not found: execution={}, jobOrder={}", executionId, jobOrder);
            return;
        }

        // 원래 trace에 연결하여 webhook resume 스팬을 생성한다
        TraceContextUtil.executeWithRestoredTrace(
                execution.getTraceParent()
                , "PipelineEngine.resumeAfterWebhook"
                , Map.of("pipeline.execution.id", executionId.toString()
                        , "pipeline.job.order", String.valueOf(jobOrder))
                , () -> doResumeAfterWebhook(execution, jobExecution, executionId, jobOrder, result, buildLog)
        );
    }

    private void doResumeAfterWebhook(PipelineExecution execution, PipelineJobExecution jobExecution
            , UUID executionId, int jobOrder, String result, String buildLog) {
        // CAS: WAITING_WEBHOOK → SUCCESS/FAILED (타임아웃 체커와의 경쟁 조건 방지)
        boolean success = "SUCCESS".equalsIgnoreCase(result);
        var targetStatus = success ? JobExecutionStatus.SUCCESS.name() : JobExecutionStatus.FAILED.name();
        var logMsg = success ? buildLog : (buildLog != null ? buildLog : "Jenkins build failed: " + result);

        int affected = jobExecutionMapper.updateStatusIfCurrent(
                jobExecution.getId(),
                JobExecutionStatus.WAITING_WEBHOOK.name(),
                targetStatus,
                logMsg,
                LocalDateTime.now());

        if (affected == 0) {
            log.warn("resumeAfterWebhook: CAS failed - jobExecution already changed: execution={}, jobOrder={}, currentStatus={}",
                    executionId, jobOrder, jobExecution.getStatus());
            return;
        }

        // DAG 모드: coordinator에 완료 통지 후 리턴
        if (dagCoordinator.isManaged(executionId)) {
            Long jobId = dagCoordinator.findJobIdByJobOrder(executionId, jobOrder);
            if (jobId != null) {
                eventProducer.publishJobExecutionChanged(execution, jobExecution, success ? JobExecutionStatus.SUCCESS : JobExecutionStatus.FAILED);
                dagCoordinator.onJobCompleted(executionId, jobOrder, jobId, success);
                log.info("Webhook resume (DAG): job {} {} (execution={})",
                        jobExecution.getJobName(), success ? "SUCCESS" : "FAILED", executionId);
            }
            return;
        }

        // 기존 순차 모드
        execution.setJobExecutions(jobExecutionMapper.findByExecutionId(executionId));

        if (success) {
            jobExecution.setLog(buildLog);
            eventProducer.publishJobExecutionChanged(execution, jobExecution, JobExecutionStatus.SUCCESS);
            log.info("Webhook resume: job {} SUCCESS (execution={})", jobExecution.getJobName(), executionId);

            // 다음 Job부터 이어서 실행 (jobOrder는 1-based, fromIndex는 0-based)
            executeFrom(execution, jobOrder, System.currentTimeMillis());
        } else {
            eventProducer.publishJobExecutionChanged(execution, jobExecution, JobExecutionStatus.FAILED);

            long duration = calculateDurationMs(execution);
            failExecution(execution, jobOrder, duration, logMsg);
            log.info("Webhook resume: job {} FAILED (execution={})", jobExecution.getJobName(), executionId);
        }
    }

    // ── private helpers ──────────────────────────────────────────────

    private void updateJobExecutionStatus(PipelineExecution execution, PipelineJobExecution jobExecution,
                                  JobExecutionStatus status, String logMessage) {
        jobExecutionMapper.updateStatus(jobExecution.getId(), status.name(), logMessage, LocalDateTime.now());
        eventProducer.publishJobExecutionChanged(execution, jobExecution, status);
    }

    private void failExecution(PipelineExecution execution, int failedJobOrder,
                               long durationMs, String errorMessage) {
        sagaCompensator.compensate(execution, failedJobOrder, jobExecutors);
        executionMapper.updateStatus(
                execution.getId(),
                PipelineStatus.FAILED.name(),
                LocalDateTime.now(),
                errorMessage);
        eventProducer.publishExecutionCompleted(
                execution,
                AvroPipelineStatus.FAILED,
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
                AvroPipelineStatus.SUCCESS,
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
