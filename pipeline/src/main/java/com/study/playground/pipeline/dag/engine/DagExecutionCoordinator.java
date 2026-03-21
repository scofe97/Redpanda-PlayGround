package com.study.playground.pipeline.dag.engine;

import com.study.playground.avro.common.AvroPipelineStatus;
import com.study.playground.pipeline.config.PipelineProperties;
import com.study.playground.pipeline.domain.*;
import com.study.playground.pipeline.dag.domain.*;
import com.study.playground.pipeline.event.PipelineEventProducer;
import com.study.playground.pipeline.dag.mapper.PipelineDefinitionMapper;
import com.study.playground.pipeline.mapper.PipelineExecutionMapper;
import com.study.playground.pipeline.mapper.PipelineJobExecutionMapper;
import com.study.playground.pipeline.dag.mapper.PipelineJobMapper;
import com.study.playground.pipeline.mapper.JobMapper;
import com.study.playground.pipeline.engine.JobExecutorRegistry;
import com.study.playground.pipeline.engine.ParameterResolver;
import com.study.playground.pipeline.engine.SagaCompensator;
import com.study.playground.pipeline.dag.event.DagEventProducer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * DAG 기반 파이프라인 실행을 조율한다.
 *
 * <p>핵심 흐름: startExecution → dispatchReadyJobs → (webhook/동기 완료) →
 * onJobCompleted → dispatchReadyJobs → ... → 전체 완료/실패 판정.</p>
 *
 * <p>동시성 모델: 실행당 {@link ReentrantLock}으로 상태 변경과 디스패치를 직렬화한다.
 * 같은 실행의 웹훅 콜백이 동시에 도착해도 check-and-dispatch가 순서대로 처리된다.
 * 실행 간에는 독립적이므로 서로 블로킹하지 않는다.</p>
 *
 * <p>메모리 상태(executionStates)는 캐시 역할만 하고, DB가 진실의 원천이다.
 * 앱 재시작 시 RUNNING 상태 실행을 DB에서 조회하여 상태를 복원한다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DagExecutionCoordinator {

    private final PipelineExecutionMapper executionMapper;
    private final PipelineJobExecutionMapper jobExecutionMapper;
    private final PipelineJobMapper jobMapper;
    private final JobMapper singleJobMapper;
    private final PipelineDefinitionMapper definitionMapper;
    private final PipelineEventProducer eventProducer;
    private final DagEventProducer dagEventProducer;
    private final SagaCompensator sagaCompensator;
    private final JobExecutorRegistry jobExecutorRegistry;
    private final DagValidator dagValidator;
    @Qualifier("jobExecutorPool")
    private final ExecutorService jobExecutorPool;
    @Qualifier("retryScheduler")
    private final ScheduledExecutorService retryScheduler;
    private final PipelineProperties props;

    /** 실행 ID → 런타임 상태. 실행 시작 시 등록, 완료 시 제거. */
    private final ConcurrentHashMap<UUID, DagExecutionState> executionStates = new ConcurrentHashMap<>();

    /** 실행 ID → Lock. 동일 실행의 상태 변경을 직렬화한다. */
    private final ConcurrentHashMap<UUID, ReentrantLock> executionLocks = new ConcurrentHashMap<>();

    // ── Feature #1: 크래시 복구 ────────────────────────────────────────

    /**
     * 앱 재시작 시 DB의 RUNNING 실행을 자동 재개한다.
     *
     * <p>RUNNING/WAITING_WEBHOOK 상태의 job은 webhook 유실을 가정하고 FAILED로 전환한다.
     * 이후 ready job이 있으면 재dispatch한다. 보수적 접근 — 필요 시 부분 재시작 API로 재개 가능.</p>
     */
    @PostConstruct
    public void recoverRunningExecutions() {
        var runningExecutions = executionMapper.findByStatus(PipelineStatus.RUNNING.name());

        for (var execution : runningExecutions) {
            // DAG 모드만 처리 (pipelineDefinitionId가 null이면 기존 순차 모드)
            if (execution.getPipelineDefinitionId() == null) {
                continue;
            }

            var executionId = execution.getId();
            log.info("[DAG-RECOVERY] Recovering execution: {}", executionId);

            try {
                // Job 목록 + 의존성 로드
                var jobs = jobMapper.findByDefinitionId(execution.getPipelineDefinitionId());
                for (var job : jobs) {
                    job.setDependsOnJobIds(jobMapper.findDependsOnJobIds(
                            execution.getPipelineDefinitionId(), job.getId()));
                }

                // JobExecution 로드
                var jobExecutions = jobExecutionMapper.findByExecutionId(executionId);
                var jobIdToJobOrder = new HashMap<Long, Integer>();
                for (var je : jobExecutions) {
                    if (je.getJobId() != null) {
                        jobIdToJobOrder.put(je.getJobId(), je.getJobOrder());
                    }
                }

                // failurePolicy 로드
                var policy = FailurePolicy.STOP_ALL;
                var definition = definitionMapper.findById(execution.getPipelineDefinitionId());
                if (definition != null && definition.getFailurePolicy() != null) {
                    policy = definition.getFailurePolicy();
                }

                // 상태 재구성
                var lock = new ReentrantLock();
                executionLocks.put(executionId, lock);
                lock.lock();
                try {
                    var state = DagExecutionState.initialize(jobs, jobIdToJobOrder, policy);
                    executionStates.put(executionId, state);

                    // 기존 job execution 상태를 state에 반영
                    for (var je : jobExecutions) {
                        if (je.getJobId() == null) continue;

                        switch (je.getStatus()) {
                            case SUCCESS -> state.markCompleted(je.getJobId());
                            case FAILED, COMPENSATED -> state.markFailed(je.getJobId());
                            case SKIPPED -> state.markSkipped(je.getJobId());
                            case RUNNING, WAITING_WEBHOOK -> {
                                // webhook 유실 가정 → FAILED 처리
                                log.warn("[DAG-RECOVERY] Marking interrupted job as FAILED: jobId={}, status={}",
                                        je.getJobId(), je.getStatus());
                                jobExecutionMapper.updateStatus(je.getId()
                                        , JobExecutionStatus.FAILED.name()
                                        , "Failed during crash recovery (interrupted)"
                                        , LocalDateTime.now());
                                state.markFailed(je.getJobId());
                            }
                            case PENDING -> { /* 그대로 둠 */ }
                        }
                    }

                    // 전체 완료 체크
                    if (state.isAllDone()) {
                        finalizeExecution(executionId, state);
                    } else if (state.hasFailure()) {
                        handleFailure(executionId, state, execution);
                    } else {
                        dispatchReadyJobs(execution);
                    }
                } finally {
                    lock.unlock();
                }

                log.info("[DAG-RECOVERY] Execution recovered: {}", executionId);
            } catch (Exception e) {
                log.error("[DAG-RECOVERY] Failed to recover execution: {}", executionId, e);
                executionMapper.updateStatus(executionId, PipelineStatus.FAILED.name()
                        , LocalDateTime.now(), "Recovery failed: " + e.getMessage());
            }
        }

        if (!runningExecutions.isEmpty()) {
            log.info("[DAG-RECOVERY] Recovered {} DAG execution(s)", runningExecutions.size());
        }
    }

    // ── 실행 시작 ──────────────────────────────────────────────────────

    /**
     * DAG 실행을 시작한다. 루트 Job(의존성 없는 Job)부터 디스패치한다.
     *
     * @param execution 실행 정보 (pipelineDefinitionId가 non-null이어야 한다)
     */
    public void startExecution(PipelineExecution execution) {
        var executionId = execution.getId();
        log.info("[DAG] Starting DAG execution: {}", executionId);

        // 실행 상태를 RUNNING으로 전환
        executionMapper.updateStatus(
                executionId
                , PipelineStatus.RUNNING.name()
                , null
                , null);

        // Job 목록 로드 + 의존성 로드
        var jobs = jobMapper.findByDefinitionId(execution.getPipelineDefinitionId());
        for (var job : jobs) {
            job.setDependsOnJobIds(jobMapper.findDependsOnJobIds(execution.getPipelineDefinitionId(), job.getId()));
        }

        // DAG 검증
        dagValidator.validate(jobs);

        // JobExecution 목록 로드 → Job ID ↔ Job order 매핑 구축
        var jobExecutions = jobExecutionMapper.findByExecutionId(executionId);
        var jobIdToJobOrder = new HashMap<Long, Integer>();
        for (var je : jobExecutions) {
            if (je.getJobId() != null) {
                jobIdToJobOrder.put(je.getJobId(), je.getJobOrder());
            }
        }

        // failurePolicy 로드
        var policy = FailurePolicy.STOP_ALL;
        var definition = definitionMapper.findById(execution.getPipelineDefinitionId());
        if (definition != null && definition.getFailurePolicy() != null) {
            policy = definition.getFailurePolicy();
        }

        // 초기 상태 구성 — lock을 먼저 등록하고 lock 안에서 state 초기화 + 디스패치
        var lock = new ReentrantLock();
        executionLocks.put(executionId, lock);
        lock.lock();
        try {
            var state = DagExecutionState.initialize(jobs, jobIdToJobOrder, policy);
            executionStates.put(executionId, state);

            // 부분 재시작: 이미 SUCCESS인 job을 완료로 사전 등록
            for (var je : jobExecutions) {
                if (je.getJobId() != null && je.getStatus() == JobExecutionStatus.SUCCESS) {
                    state.markCompleted(je.getJobId());
                    log.info("[DAG] Pre-registered completed job from previous run: jobId={}", je.getJobId());
                }
            }

            // 루트 Job 디스패치
            dispatchReadyJobs(execution);
        } finally {
            lock.unlock();
        }
    }

    // ── Job 완료 처리 ──────────────────────────────────────────────────

    /**
     * Job 완료 시 호출된다. 웹훅 콜백 또는 동기 실행 완료 후 호출.
     *
     * @param executionId 실행 ID
     * @param jobOrder    완료된 Job 순서
     * @param jobId       완료된 Job ID
     * @param success     성공 여부
     */
    public void onJobCompleted(UUID executionId, int jobOrder, Long jobId, boolean success) {
        var lock = executionLocks.get(executionId);
        if (lock == null) {
            log.warn("[DAG] No lock found for execution: {}", executionId);
            return;
        }

        lock.lock();
        try {
            var state = executionStates.get(executionId);
            if (state == null) {
                log.warn("[DAG] No state found for execution: {}", executionId);
                return;
            }

            // 상태 업데이트
            state.removeRunning(jobId);
            if (success) {
                state.markCompleted(jobId);
                log.info("[DAG] Job completed successfully: jobId={}, execution={}", jobId, executionId);

                // BUILD Job 완료 시 configJson의 GAV로 Nexus URL을 구성하여 contextJson에 저장
                var completedJob = state.jobs().get(jobId);
                if (completedJob != null && completedJob.getJobType() == PipelineJobType.BUILD) {
                    saveArtifactUrlToContext(executionId, completedJob);
                }
            } else {
                state.markFailed(jobId);
                log.warn("[DAG] Job failed: jobId={}, execution={}", jobId, executionId);
            }

            // DAG Job 완료 이벤트 발행
            publishDagJobCompletedEvent(executionId, jobId, jobOrder, state, success);

            // 전체 완료 체크
            if (state.isAllDone()) {
                finalizeExecution(executionId, state);
                return;
            }

            // 실패 처리
            if (!success) {
                var execution = executionMapper.findById(executionId);
                if (execution != null) {
                    handleFailure(executionId, state, execution);
                }
                return;
            }

            // 성공이지만 기존 실패가 있으면 정책에 따라 분기
            if (state.hasFailure()) {
                var execution = executionMapper.findById(executionId);
                if (execution != null) {
                    handleFailure(executionId, state, execution);
                }
                return;
            }

            // 새로 ready된 Job 디스패치
            var execution = executionMapper.findById(executionId);
            if (execution != null) {
                dispatchReadyJobs(execution);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 웹훅 타임아웃 시 호출된다.
     */
    public void onJobFailed(UUID executionId, int jobOrder, Long jobId) {
        onJobCompleted(executionId, jobOrder, jobId, false);
    }

    /**
     * 특정 실행이 DAG 모드인지 확인한다.
     */
    public boolean isManaged(UUID executionId) {
        return executionStates.containsKey(executionId);
    }

    /**
     * jobOrder로 jobId를 역참조한다.
     */
    public Long findJobIdByJobOrder(UUID executionId, int jobOrder) {
        var state = executionStates.get(executionId);
        if (state == null) return null;
        for (var entry : state.jobIdToJobOrder().entrySet()) {
            if (entry.getValue() == jobOrder) {
                return entry.getKey();
            }
        }
        return null;
    }

    // ── Feature #4: Stale 실행 정리 강화 ───────────────────────────────

    /**
     * 완료된 실행 상태를 정리하고, staleExecutionTimeoutMinutes 초과 RUNNING 실행을 FAILED 처리한다.
     */
    @Scheduled(fixedDelay = 300_000) // 5분
    public void cleanupStaleExecutions() {
        // 1) 메모리에서 이미 완료된 상태 정리
        var staleIds = new ArrayList<UUID>();
        for (var entry : executionStates.entrySet()) {
            if (entry.getValue().isAllDone()) {
                staleIds.add(entry.getKey());
            }
        }
        for (var id : staleIds) {
            executionStates.remove(id);
            executionLocks.remove(id);
            log.info("[DAG] Cleaned up completed execution state: {}", id);
        }

        // 2) DB에서 staleExecutionTimeoutMinutes 초과 RUNNING 실행 FAILED 처리
        var runningExecutions = executionMapper.findByStatus(PipelineStatus.RUNNING.name());
        for (var execution : runningExecutions) {
            if (execution.getStartedAt() == null) continue;

            long minutesElapsed = Duration.between(execution.getStartedAt(), LocalDateTime.now()).toMinutes();
            if (minutesElapsed > props.staleExecutionTimeoutMinutes()) {
                var executionId = execution.getId();
                log.warn("[DAG] Stale execution detected ({}min > {}min limit): {}",
                        minutesElapsed, props.staleExecutionTimeoutMinutes(), executionId);

                // 비종료 job을 FAILED 마킹
                var jobExecutions = jobExecutionMapper.findByExecutionId(executionId);
                for (var je : jobExecutions) {
                    if (je.getStatus() == JobExecutionStatus.PENDING
                            || je.getStatus() == JobExecutionStatus.RUNNING
                            || je.getStatus() == JobExecutionStatus.WAITING_WEBHOOK) {
                        jobExecutionMapper.updateStatus(je.getId()
                                , JobExecutionStatus.FAILED.name()
                                , "Stale execution timeout (%dmin)".formatted(minutesElapsed)
                                , LocalDateTime.now());
                    }
                }

                executionMapper.updateStatus(executionId, PipelineStatus.FAILED.name()
                        , LocalDateTime.now()
                        , "Stale execution timeout after %d minutes".formatted(minutesElapsed));

                // 메모리 상태 정리
                executionStates.remove(executionId);
                executionLocks.remove(executionId);
            }
        }
    }

    // ── private helpers ──────────────────────────────────────────────

    private void dispatchReadyJobs(PipelineExecution execution) {
        var executionId = execution.getId();
        var lock = executionLocks.get(executionId);

        // 이미 lock을 들고 있을 수 있으므로 tryLock 대신 조건 분기
        boolean needsLock = !lock.isHeldByCurrentThread();
        if (needsLock) lock.lock();

        try {
            var state = executionStates.get(executionId);
            if (state == null) return;

            var readyJobIds = state.findReadyJobIds();
            if (readyJobIds.isEmpty()) return;

            // containerCap 제한
            int available = props.maxConcurrentJobs() - state.runningCount();
            if (available <= 0) {
                log.info("[DAG] All slots occupied ({}/{}), waiting for completion",
                        state.runningCount(), props.maxConcurrentJobs());
                return;
            }

            var toDispatch = readyJobIds.subList(0, Math.min(readyJobIds.size(), available));

            for (Long jobId : toDispatch) {
                state.markRunning(jobId);
                var job = state.jobs().get(jobId);
                var jobOrder = state.jobIdToJobOrder().get(jobId);

                log.info("[DAG] Dispatching job: {} (type={}, jobOrder={}, execution={})",
                        job.getJobName(), job.getJobType(), jobOrder, executionId);

                // DAG Job 디스패치 이벤트 발행
                dagEventProducer.publishDagJobDispatched(
                        executionId.toString(), jobId, job.getJobName()
                        , job.getJobType().name(), jobOrder);

                jobExecutorPool.submit(() -> executeJob(execution, job, jobOrder));
            }
        } finally {
            if (needsLock) lock.unlock();
        }
    }

    private void executeJob(PipelineExecution execution, PipelineJob job, Integer jobOrder) {
        var executionId = execution.getId();
        var je = jobExecutionMapper.findByExecutionIdAndJobOrder(executionId, jobOrder);
        if (je == null) {
            log.error("[DAG] JobExecution not found: execution={}, jobOrder={}", executionId, jobOrder);
            onJobCompleted(executionId, jobOrder, job.getId(), false);
            return;
        }

        // 사용자 파라미터를 jobExecution에 전달 (Step Executor가 참조)
        var userParams = execution.parameters();
        if (!userParams.isEmpty()) {
            je.setUserParams(userParams);
        }

        // 실행 컨텍스트 전달 (이전 Job의 출력물을 다음 Job이 참조)
        var execContext = execution.context();

        // DEPLOY Job이 BUILD Job에 의존하면, 해당 빌드의 ARTIFACT_URL을 자동 주입
        if (job.getJobType() == PipelineJobType.DEPLOY && !execContext.isEmpty()) {
            var deps = job.getDependsOnJobIds();
            if (deps != null) {
                var state = executionStates.get(executionId);
                for (var depId : deps) {
                    var depJob = state != null ? state.jobs().get(depId) : null;
                    if (depJob != null && depJob.getJobType() == PipelineJobType.BUILD) {
                        var depArtifactUrl = execContext.get("ARTIFACT_URL_" + depId);
                        if (depArtifactUrl != null) {
                            execContext.put("ARTIFACT_URL", depArtifactUrl);
                            log.debug("[DAG] DEPLOY Job {} ← BUILD Job {}의 ARTIFACT_URL 자동 주입", job.getId(), depId);
                            // 첫 번째 BUILD 의존성의 아티팩트만 사용. 다중 BUILD→단일 DEPLOY 시 확장 필요.
                            break;
                        }
                    }
                }
            }
        }

        if (!execContext.isEmpty()) {
            je.setExecutionContext(execContext);
        }

        // Job configJson의 ${PARAM} 플레이스홀더를 사용자 파라미터 + 실행 컨텍스트로 치환
        var configJson = job.getConfigJson();
        if (configJson != null) {
            // 컨텍스트 + 사용자 파라미터를 병합 (사용자 파라미터가 우선)
            var mergedParams = new java.util.HashMap<>(execContext);
            mergedParams.putAll(userParams);
            if (!mergedParams.isEmpty()) {
                je.setResolvedConfigJson(ParameterResolver.resolve(configJson, mergedParams));
            } else {
                je.setResolvedConfigJson(configJson);
            }
        }

        // RUNNING으로 전환
        jobExecutionMapper.updateStatus(je.getId(), JobExecutionStatus.RUNNING.name(), null, LocalDateTime.now());
        eventProducer.publishJobExecutionChanged(execution, je, JobExecutionStatus.RUNNING);

        try {
            var executor = jobExecutorRegistry.getExecutor(job.getJobType());
            if (executor == null) {
                throw new IllegalStateException("No executor for job type: " + job.getJobType());
            }
            executor.execute(execution, je);

            // Break-and-Resume: webhook 대기
            if (je.isWaitingForWebhook()) {
                jobExecutionMapper.updateStatus(je.getId(), JobExecutionStatus.WAITING_WEBHOOK.name()
                        , "Waiting for Jenkins webhook callback...", LocalDateTime.now());
                eventProducer.publishJobExecutionChanged(execution, je, JobExecutionStatus.WAITING_WEBHOOK);
                log.info("[DAG] Job {} waiting for webhook (execution={})", job.getJobName(), executionId);
                // 스레드 반환 — webhook 도착 시 onJobCompleted()에서 재개
                return;
            }

            // 동기 완료
            jobExecutionMapper.updateStatus(je.getId(), JobExecutionStatus.SUCCESS.name(), je.getLog(), LocalDateTime.now());
            eventProducer.publishJobExecutionChanged(execution, je, JobExecutionStatus.SUCCESS);
            onJobCompleted(executionId, jobOrder, job.getId(), true);

        } catch (Exception e) {
            // Feature #3: Job 재시도 (동기 실행 예외만)
            int currentRetry = je.getRetryCount();
            if (currentRetry < props.jobMaxRetries()) {
                jobExecutionMapper.incrementRetryCount(je.getId());
                int nextRetry = currentRetry + 1;
                long delaySeconds = 1L << currentRetry; // 2^retryCount: 1s, 2s, 4s

                log.warn("[DAG] Job execution failed, scheduling retry {}/{} in {}s: {} (execution={})",
                        nextRetry, props.jobMaxRetries(), delaySeconds, job.getJobName(), executionId, e);

                jobExecutionMapper.updateStatus(je.getId(), JobExecutionStatus.PENDING.name()
                        , "Retry %d/%d scheduled (delay: %ds): %s".formatted(
                                nextRetry, props.jobMaxRetries(), delaySeconds, e.getMessage())
                        , null);

                retryScheduler.schedule(
                        () -> executeJob(execution, job, jobOrder)
                        , delaySeconds, TimeUnit.SECONDS);
                return;
            }

            log.error("[DAG] Job execution failed after {} retries: {} (execution={})",
                    currentRetry, job.getJobName(), executionId, e);
            jobExecutionMapper.updateStatus(je.getId(), JobExecutionStatus.FAILED.name(), e.getMessage(), LocalDateTime.now());
            eventProducer.publishJobExecutionChanged(execution, je, JobExecutionStatus.FAILED);
            onJobCompleted(executionId, jobOrder, job.getId(), false);
        }
    }

    /**
     * 실패 발생 시 failurePolicy에 따라 분기 처리한다.
     */
    private void handleFailure(UUID executionId, DagExecutionState state, PipelineExecution execution) {
        switch (state.failurePolicy()) {
            case STOP_ALL -> handleStopAll(executionId, state);
            case SKIP_DOWNSTREAM -> handleSkipDownstream(executionId, state, execution);
            case FAIL_FAST -> handleFailFast(executionId, state);
        }
    }

    /**
     * STOP_ALL: 새 Job 디스패치를 중단하고, 실행 중 Job 완료를 기다린다.
     */
    private void handleStopAll(UUID executionId, DagExecutionState state) {
        if (state.runningCount() == 0) {
            skipPendingJobs(executionId, state);
            finalizeExecution(executionId, state);
        }
        // running이 남아 있으면 그들의 완료를 기다림 (새 디스패치 없음)
    }

    /**
     * SKIP_DOWNSTREAM: 실패한 Job의 전이적 하위만 SKIP하고, 다른 브랜치는 계속 실행한다.
     */
    private void handleSkipDownstream(UUID executionId, DagExecutionState state, PipelineExecution execution) {
        // 실패한 각 job의 downstream을 SKIP
        for (Long failedJobId : new ArrayList<>(state.failedJobIds())) {
            var downstream = state.allDownstream(failedJobId);
            for (Long downId : downstream) {
                if (!state.isTerminated(downId)) {
                    state.markSkipped(downId);
                    var jobOrder = state.jobIdToJobOrder().get(downId);
                    if (jobOrder != null) {
                        var je = jobExecutionMapper.findByExecutionIdAndJobOrder(executionId, jobOrder);
                        if (je != null && je.getStatus() == JobExecutionStatus.PENDING) {
                            jobExecutionMapper.updateStatus(je.getId(), JobExecutionStatus.SKIPPED.name()
                                    , "Skipped: upstream job failed (SKIP_DOWNSTREAM policy)"
                                    , LocalDateTime.now());
                        }
                    }
                }
            }
        }

        // 전체 완료 체크
        if (state.isAllDone()) {
            finalizeExecution(executionId, state);
        } else {
            // 독립 브랜치의 ready job 디스패치
            dispatchReadyJobs(execution);
        }
    }

    /**
     * FAIL_FAST: 모든 PENDING Job을 즉시 SKIP, RUNNING 완료 대기.
     */
    private void handleFailFast(UUID executionId, DagExecutionState state) {
        skipPendingJobs(executionId, state);

        if (state.runningCount() == 0) {
            finalizeExecution(executionId, state);
        }
        // running이 남아 있으면 완료 대기
    }

    private void skipPendingJobs(UUID executionId, DagExecutionState state) {
        var jobExecutions = jobExecutionMapper.findByExecutionId(executionId);
        for (var je : jobExecutions) {
            if (je.getStatus() == JobExecutionStatus.PENDING) {
                jobExecutionMapper.updateStatus(je.getId(), JobExecutionStatus.SKIPPED.name()
                        , "Skipped due to upstream failure", LocalDateTime.now());
                if (je.getJobId() != null) {
                    state.markSkipped(je.getJobId());
                }
            }
        }
    }

    private void finalizeExecution(UUID executionId, DagExecutionState state) {
        var execution = executionMapper.findById(executionId);
        if (execution == null) return;

        execution.setJobExecutions(jobExecutionMapper.findByExecutionId(executionId));
        long durationMs = calculateDurationMs(execution);

        if (state.hasFailure()) {
            // SAGA 보상: 역방향 위상 순서로 완료된 Job 보상
            log.warn("[DAG] Execution failed, starting SAGA compensation: {}", executionId);
            var reverseOrder = state.completedJobIdsInReverseTopologicalOrder();
            compensateDag(execution, reverseOrder, state);

            var errorMsg = "DAG execution failed: %d job(s) failed".formatted(state.failedCount());
            executionMapper.updateStatus(executionId, PipelineStatus.FAILED.name()
                    , LocalDateTime.now(), errorMsg);
            eventProducer.publishExecutionCompleted(execution
                    , AvroPipelineStatus.FAILED, durationMs, errorMsg);
        } else {
            executionMapper.updateStatus(executionId, PipelineStatus.SUCCESS.name()
                    , LocalDateTime.now(), null);
            eventProducer.publishExecutionCompleted(execution
                    , AvroPipelineStatus.SUCCESS, durationMs, null);
            log.info("[DAG] Execution completed successfully: {} in {}ms", executionId, durationMs);
        }

        // 상태 정리
        executionStates.remove(executionId);
        executionLocks.remove(executionId);
    }

    private void compensateDag(
            PipelineExecution execution
            , List<Long> reverseJobIds
            , DagExecutionState state) {
        var jobExecutors = jobExecutorRegistry.asJobTypeMap();

        for (Long jobId : reverseJobIds) {
            var jobOrder = state.jobIdToJobOrder().get(jobId);
            if (jobOrder == null) continue;

            var je = jobExecutionMapper.findByExecutionIdAndJobOrder(execution.getId(), jobOrder);
            if (je == null || je.getStatus() != JobExecutionStatus.SUCCESS) continue;

            try {
                var executor = jobExecutors.get(je.getJobType());
                if (executor != null) {
                    executor.compensate(execution, je);
                }
                jobExecutionMapper.updateStatus(je.getId(), JobExecutionStatus.COMPENSATED.name()
                        , "Compensated after DAG saga rollback", LocalDateTime.now());
                eventProducer.publishJobExecutionChanged(execution, je, JobExecutionStatus.COMPENSATED);
                log.info("[DAG-SAGA] Compensated job: {} (jobOrder={})", je.getJobName(), jobOrder);
            } catch (Exception e) {
                log.error("[DAG-SAGA] Compensation FAILED for job: {} - MANUAL INTERVENTION REQUIRED",
                        je.getJobName(), e);
                jobExecutionMapper.updateStatus(je.getId(), JobExecutionStatus.FAILED.name()
                        , "COMPENSATION_FAILED: " + e.getMessage(), LocalDateTime.now());
            }
        }
    }

    private void publishDagJobCompletedEvent(
            UUID executionId, Long jobId, int jobOrder
            , DagExecutionState state, boolean success) {
        var job = state.jobs().get(jobId);
        if (job == null) return;

        var je = jobExecutionMapper.findByExecutionIdAndJobOrder(executionId, jobOrder);
        long durationMs = 0;
        int retryCount = 0;
        String logSnippet = null;

        if (je != null) {
            retryCount = je.getRetryCount();
            logSnippet = je.getLog() != null && je.getLog().length() > 500
                    ? je.getLog().substring(0, 500) : je.getLog();
            if (je.getStartedAt() != null) {
                durationMs = Duration.between(je.getStartedAt(), LocalDateTime.now()).toMillis();
            }
        }

        dagEventProducer.publishDagJobCompleted(
                executionId.toString(), jobId, job.getJobName()
                , job.getJobType().name(), jobOrder
                , success ? "SUCCESS" : "FAILED"
                , durationMs, retryCount, logSnippet);
    }

    private long calculateDurationMs(PipelineExecution execution) {
        return execution.getStartedAt() != null
                ? Duration.between(execution.getStartedAt(), LocalDateTime.now()).toMillis()
                : 0;
    }

    /**
     * BUILD Job 완료 시 configJson의 GAV 좌표로 Nexus 아티팩트 URL을 구성하여 contextJson에 저장한다.
     * 키는 ARTIFACT_URL_{jobId}로, 여러 BUILD Job이 있을 때 각각 구분된다.
     * DEPLOY Job의 configJson에서 ${ARTIFACT_URL_{jobId}} 플레이스홀더로 참조할 수 있다.
     */
    private void saveArtifactUrlToContext(UUID executionId, PipelineJob job) {
        try {
            var execution = executionMapper.findById(executionId);
            if (execution == null) return;

            // configJson에서 GAV 좌표 추출 (userParams로 ${PARAM} 치환 후)
            var configJson = job.getConfigJson();
            if (configJson == null || configJson.isBlank()) return;

            var userParams = execution.parameters();
            var resolved = !userParams.isEmpty()
                    ? ParameterResolver.resolve(configJson, userParams)
                    : configJson;

            var configMap = OBJECT_MAPPER.readValue(resolved
                    , new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, String>>() {});

            // Nexus URL: preset에서 조회 (category=LIBRARY), 없으면 configJson 폴백
            var nexusUrl = singleJobMapper.findToolUrlByPresetIdAndCategory(job.getPresetId(), "LIBRARY");
            if (nexusUrl == null || nexusUrl.isBlank()) {
                nexusUrl = configMap.getOrDefault("NEXUS_URL", "");
            }
            var groupId = configMap.getOrDefault("GROUP_ID", "");
            var artifactId = configMap.getOrDefault("ARTIFACT_ID", "");
            var version = configMap.getOrDefault("VERSION", "");
            var packaging = configMap.getOrDefault("PACKAGING", "war");

            if (nexusUrl.isEmpty() || groupId.isEmpty() || artifactId.isEmpty() || version.isEmpty()) {
                log.debug("[DAG] BUILD Job {}에 GAV 정보 부족 — contextJson 스킵", job.getId());
                return;
            }

            var groupPath = groupId.replace('.', '/');
            var artifactUrl = "%s/repository/maven-releases/%s/%s/%s/%s-%s.%s".formatted(
                    nexusUrl, groupPath, artifactId, version, artifactId, version, packaging);

            execution.putContext("ARTIFACT_URL_" + job.getId(), artifactUrl);
            executionMapper.updateContextJson(executionId, execution.getContextJson());
            log.info("[DAG] BUILD Job {} 완료 → contextJson에 ARTIFACT_URL_{} 저장: {}",
                    job.getId(), job.getId(), artifactUrl);
        } catch (Exception e) {
            log.warn("[DAG] ARTIFACT_URL 저장 실패 (executionId={}, jobId={}): {}",
                    executionId, job.getId(), e.getMessage());
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper OBJECT_MAPPER
            = new com.fasterxml.jackson.databind.ObjectMapper();
}
