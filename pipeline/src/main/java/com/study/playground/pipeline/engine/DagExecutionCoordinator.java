package com.study.playground.pipeline.engine;

import com.study.playground.pipeline.domain.*;
import com.study.playground.pipeline.event.PipelineEventProducer;
import com.study.playground.pipeline.mapper.PipelineExecutionMapper;
import com.study.playground.pipeline.mapper.PipelineJobExecutionMapper;
import com.study.playground.pipeline.mapper.PipelineJobMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
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
 * 앱 재시작 시 RUNNING 상태 실행을 DB에서 조회하여 상태를 복원할 수 있다.</p>
 */
@Slf4j
@Component
public class DagExecutionCoordinator {

    /** Jenkins containerCap에 맞춘 동시 실행 제한 */
    private static final int MAX_CONCURRENT_JOBS = 3;

    private final PipelineExecutionMapper executionMapper;
    private final PipelineJobExecutionMapper jobExecutionMapper;
    private final PipelineJobMapper jobMapper;
    private final PipelineEventProducer eventProducer;
    private final SagaCompensator sagaCompensator;
    private final JobExecutorRegistry jobExecutorRegistry;
    private final DagValidator dagValidator;
    private final ExecutorService jobExecutorPool;

    /** 실행 ID → 런타임 상태. 실행 시작 시 등록, 완료 시 제거. */
    private final ConcurrentHashMap<UUID, DagExecutionState> executionStates = new ConcurrentHashMap<>();

    /** 실행 ID → Lock. 동일 실행의 상태 변경을 직렬화한다. */
    private final ConcurrentHashMap<UUID, ReentrantLock> executionLocks = new ConcurrentHashMap<>();

    public DagExecutionCoordinator(
            PipelineExecutionMapper executionMapper,
            PipelineJobExecutionMapper jobExecutionMapper,
            PipelineJobMapper jobMapper,
            PipelineEventProducer eventProducer,
            SagaCompensator sagaCompensator,
            JobExecutorRegistry jobExecutorRegistry,
            DagValidator dagValidator,
            @Qualifier("jobExecutorPool") ExecutorService jobExecutorPool) {
        this.executionMapper = executionMapper;
        this.jobExecutionMapper = jobExecutionMapper;
        this.jobMapper = jobMapper;
        this.eventProducer = eventProducer;
        this.sagaCompensator = sagaCompensator;
        this.jobExecutorRegistry = jobExecutorRegistry;
        this.dagValidator = dagValidator;
        this.jobExecutorPool = jobExecutorPool;
    }

    /**
     * DAG 실행을 시작한다. 루트 Job(의존성 없는 Job)부터 디스패치한다.
     *
     * @param execution 실행 정보 (pipelineDefinitionId가 non-null이어야 한다)
     */
    public void startExecution(PipelineExecution execution) {
        UUID executionId = execution.getId();
        log.info("[DAG] Starting DAG execution: {}", executionId);

        // 실행 상태를 RUNNING으로 전환
        executionMapper.updateStatus(
                executionId
                , PipelineStatus.RUNNING.name()
                , null
                , null);

        // Job 목록 로드 + 의존성 로드
        List<PipelineJob> jobs = jobMapper.findByDefinitionId(execution.getPipelineDefinitionId());
        for (var job : jobs) {
            job.setDependsOnJobIds(jobMapper.findDependsOnJobIds(execution.getPipelineDefinitionId(), job.getId()));
        }

        // DAG 검증
        dagValidator.validate(jobs);

        // JobExecution 목록 로드 → Job ID ↔ Job order 매핑 구축
        List<PipelineJobExecution> jobExecutions = jobExecutionMapper.findByExecutionId(executionId);
        Map<Long, Integer> jobIdToJobOrder = new HashMap<>();
        for (var je : jobExecutions) {
            if (je.getJobId() != null) {
                jobIdToJobOrder.put(je.getJobId(), je.getJobOrder());
            }
        }

        // 초기 상태 구성 — lock을 먼저 등록하고 lock 안에서 state 초기화 + 디스패치
        ReentrantLock lock = new ReentrantLock();
        executionLocks.put(executionId, lock);
        lock.lock();
        try {
            DagExecutionState state = DagExecutionState.initialize(jobs, jobIdToJobOrder);
            executionStates.put(executionId, state);

            // 루트 Job 디스패치
            dispatchReadyJobs(execution);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Job 완료 시 호출된다. 웹훅 콜백 또는 동기 실행 완료 후 호출.
     *
     * @param executionId 실행 ID
     * @param jobOrder    완료된 Job 순서
     * @param jobId       완료된 Job ID
     * @param success     성공 여부
     */
    public void onJobCompleted(UUID executionId, int jobOrder, Long jobId, boolean success) {
        ReentrantLock lock = executionLocks.get(executionId);
        if (lock == null) {
            log.warn("[DAG] No lock found for execution: {}", executionId);
            return;
        }

        lock.lock();
        try {
            DagExecutionState state = executionStates.get(executionId);
            if (state == null) {
                log.warn("[DAG] No state found for execution: {}", executionId);
                return;
            }

            // 상태 업데이트
            state.removeRunning(jobId);
            if (success) {
                state.markCompleted(jobId);
                log.info("[DAG] Job completed successfully: jobId={}, execution={}", jobId, executionId);
            } else {
                state.markFailed(jobId);
                log.warn("[DAG] Job failed: jobId={}, execution={}", jobId, executionId);
            }

            // 전체 완료 체크
            if (state.isAllDone()) {
                finalizeExecution(executionId, state);
                return;
            }

            // 실패가 있으면 새 Job을 디스패치하지 않고, 실행 중인 Job 완료를 기다린다
            if (state.hasFailure()) {
                if (state.runningCount() == 0) {
                    // 모든 실행 중 Job이 끝났으므로 최종 판정
                    skipPendingJobs(executionId, state);
                    finalizeExecution(executionId, state);
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
        DagExecutionState state = executionStates.get(executionId);
        if (state == null) return null;
        for (var entry : state.jobIdToJobOrder().entrySet()) {
            if (entry.getValue() == jobOrder) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 완료된 실행 상태를 정리한다. finalizeExecution에서 정상 제거되지만,
     * 비정상 종료 등으로 남아 있는 stale 항목을 주기적으로 정리하는 안전망이다.
     */
    @Scheduled(fixedDelay = 300_000) // 5분
    public void cleanupStaleExecutions() {
        var staleIds = new ArrayList<UUID>();
        for (var entry : executionStates.entrySet()) {
            if (entry.getValue().isAllDone()) {
                staleIds.add(entry.getKey());
            }
        }
        for (var id : staleIds) {
            executionStates.remove(id);
            executionLocks.remove(id);
            log.info("[DAG] Cleaned up stale execution state: {}", id);
        }
    }

    // ── private helpers ──────────────────────────────────────────────

    private void dispatchReadyJobs(PipelineExecution execution) {
        UUID executionId = execution.getId();
        ReentrantLock lock = executionLocks.get(executionId);

        // 이미 lock을 들고 있을 수 있으므로 tryLock 대신 조건 분기
        boolean needsLock = !lock.isHeldByCurrentThread();
        if (needsLock) lock.lock();

        try {
            DagExecutionState state = executionStates.get(executionId);
            if (state == null) return;

            List<Long> readyJobIds = state.findReadyJobIds();
            if (readyJobIds.isEmpty()) return;

            // containerCap 제한
            int available = MAX_CONCURRENT_JOBS - state.runningCount();
            if (available <= 0) {
                log.info("[DAG] All slots occupied ({}/{}), waiting for completion",
                        state.runningCount(), MAX_CONCURRENT_JOBS);
                return;
            }

            List<Long> toDispatch = readyJobIds.subList(0, Math.min(readyJobIds.size(), available));

            for (Long jobId : toDispatch) {
                state.markRunning(jobId);
                PipelineJob job = state.jobs().get(jobId);
                Integer jobOrder = state.jobIdToJobOrder().get(jobId);

                log.info("[DAG] Dispatching job: {} (type={}, jobOrder={}, execution={})",
                        job.getJobName(), job.getJobType(), jobOrder, executionId);

                jobExecutorPool.submit(() -> executeJob(execution, job, jobOrder));
            }
        } finally {
            if (needsLock) lock.unlock();
        }
    }

    private void executeJob(PipelineExecution execution, PipelineJob job, Integer jobOrder) {
        UUID executionId = execution.getId();
        PipelineJobExecution je = jobExecutionMapper.findByExecutionIdAndJobOrder(executionId, jobOrder);
        if (je == null) {
            log.error("[DAG] JobExecution not found: execution={}, jobOrder={}", executionId, jobOrder);
            onJobCompleted(executionId, jobOrder, job.getId(), false);
            return;
        }

        // RUNNING으로 전환
        jobExecutionMapper.updateStatus(je.getId(), JobExecutionStatus.RUNNING.name(), null, LocalDateTime.now());
        eventProducer.publishJobExecutionChanged(execution, je, JobExecutionStatus.RUNNING);

        try {
            PipelineJobExecutor executor = jobExecutorRegistry.getExecutor(job.getJobType());
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
            log.error("[DAG] Job execution failed: {} (execution={})", job.getJobName(), executionId, e);
            jobExecutionMapper.updateStatus(je.getId(), JobExecutionStatus.FAILED.name(), e.getMessage(), LocalDateTime.now());
            eventProducer.publishJobExecutionChanged(execution, je, JobExecutionStatus.FAILED);
            onJobCompleted(executionId, jobOrder, job.getId(), false);
        }
    }

    private void skipPendingJobs(UUID executionId, DagExecutionState state) {
        List<PipelineJobExecution> jobExecutions = jobExecutionMapper.findByExecutionId(executionId);
        for (var je : jobExecutions) {
            if (je.getStatus() == JobExecutionStatus.PENDING) {
                jobExecutionMapper.updateStatus(je.getId(), JobExecutionStatus.SKIPPED.name()
                        , "Skipped due to upstream failure", LocalDateTime.now());
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
            List<Long> reverseOrder = state.completedJobIdsInReverseTopologicalOrder();
            compensateDag(execution, reverseOrder, state);

            String errorMsg = "DAG execution failed: %d job(s) failed".formatted(state.failedCount());
            executionMapper.updateStatus(executionId, PipelineStatus.FAILED.name()
                    , LocalDateTime.now(), errorMsg);
            eventProducer.publishExecutionCompleted(execution
                    , com.study.playground.avro.common.PipelineStatus.FAILED, durationMs, errorMsg);
        } else {
            executionMapper.updateStatus(executionId, PipelineStatus.SUCCESS.name()
                    , LocalDateTime.now(), null);
            eventProducer.publishExecutionCompleted(execution
                    , com.study.playground.avro.common.PipelineStatus.SUCCESS, durationMs, null);
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
            Integer jobOrder = state.jobIdToJobOrder().get(jobId);
            if (jobOrder == null) continue;

            PipelineJobExecution je = jobExecutionMapper.findByExecutionIdAndJobOrder(execution.getId(), jobOrder);
            if (je == null || je.getStatus() != JobExecutionStatus.SUCCESS) continue;

            try {
                PipelineJobExecutor executor = jobExecutors.get(je.getJobType());
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

    private long calculateDurationMs(PipelineExecution execution) {
        return execution.getStartedAt() != null
                ? Duration.between(execution.getStartedAt(), LocalDateTime.now()).toMillis()
                : 0;
    }
}
