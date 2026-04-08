package com.study.playground.executor.execution.application;

import com.study.playground.executor.config.ExecutorProperties;
import com.study.playground.executor.execution.domain.model.ExecutionJob;
import com.study.playground.executor.execution.domain.model.ExecutionJobStatus;
import com.study.playground.executor.execution.domain.port.in.EvaluateDispatchUseCase;
import com.study.playground.executor.execution.domain.port.out.ExecutionJobPort;
import com.study.playground.executor.execution.domain.port.out.JenkinsQueryPort;
import com.study.playground.executor.execution.domain.port.out.JobDefinitionQueryPort;
import com.study.playground.executor.execution.domain.port.out.PublishExecuteCommandPort;
import com.study.playground.executor.execution.domain.service.DispatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * EvaluateDispatchUseCase 구현.
 *
 * tryDispatch() 흐름:
 *   1. PENDING Job 조회 (우선순위 순, FOR UPDATE SKIP LOCKED)
 *   2. 각 Job에 대해:
 *      a. 동일 jobId가 이미 QUEUED/RUNNING → skip (중복 실행 방지)
 *      b. job → Jenkins 인스턴스 매핑
 *      c. 해당 Jenkins에 슬롯 있는가? → 없으면 skip, 다음 job 검사
 *      d. 슬롯 있으면 → QUEUED → 실행 토픽 발행
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DispatchEvaluatorService implements EvaluateDispatchUseCase {

    private static final List<ExecutionJobStatus> ACTIVE_STATUSES =
            List.of(
                    ExecutionJobStatus.QUEUED
                    , ExecutionJobStatus.SUBMITTED
                    , ExecutionJobStatus.RUNNING
            );

    private final PublishExecuteCommandPort publishPort;
    private final ExecutionJobPort jobPort;
    private final JenkinsQueryPort jenkinsQueryPort;
    private final JobDefinitionQueryPort jobDefinitionQueryPort;
    private final DispatchService dispatchService;
    private final ExecutorProperties properties;

    @Override
    @Transactional
    public void tryDispatch() {
        List<ExecutionJob> pendingJobs = jobPort.findDispatchableJobs(
                properties.getMaxBatchSize());

        if (pendingJobs.isEmpty()) {
            return;
        }

        Map<Long, List<ExecutionJob>> jobsByInstance = new LinkedHashMap<>();

        for (ExecutionJob job : pendingJobs) {
            try {
                var defInfo = jobDefinitionQueryPort.load(job.getJobId());
                jobsByInstance.computeIfAbsent(defInfo.jenkinsInstanceId(), ignored -> new ArrayList<>())
                        .add(job);
            } catch (Exception e) {
                log.error("[Dispatch] Job definition lookup failed: jobExcnId={}, jobId={}, error={}"
                        , job.getJobExcnId(), job.getJobId(), e.getMessage(), e);
                boolean retried = retryPendingJob(job);
                jobPort.save(job);
                if (retried) {
                    log.warn("[Dispatch] Retry #{} after missing definition: jobExcnId={}"
                            , job.getRetryCnt(), job.getJobExcnId());
                } else {
                    log.error("[Dispatch] Marked FAILURE after missing definition: jobExcnId={}"
                            , job.getJobExcnId());
                }
            }
        }

        for (var entry : jobsByInstance.entrySet()) {
            try {
                dispatchForInstance(entry.getKey(), entry.getValue());
            } catch (Exception e) {
                log.error("[Dispatch] Failed for instanceId={}: {}"
                        , entry.getKey(), e.getMessage(), e);
            }
        }
    }

    private boolean retryPendingJob(ExecutionJob job) {
        if (job.canRetry(properties.getJobMaxRetries())) {
            job.incrementRetry();
            return true;
        }

        job.transitionTo(ExecutionJobStatus.FAILURE);
        return false;
    }

    private void dispatchForInstance(long instanceId, List<ExecutionJob> jobs) {
        if (!jenkinsQueryPort.isReachable(instanceId)) {
            log.warn("[Dispatch] Jenkins unreachable: instanceId={}", instanceId);
            return;
        }

        int activeCount = jobPort.countActiveJobsByJenkinsInstanceId(instanceId, ACTIVE_STATUSES);
        int maxSlots = jenkinsQueryPort.getMaxExecutors(instanceId);
        int remainingSlots = maxSlots - activeCount;

        if (remainingSlots <= 0) {
            log.debug("[Dispatch] No slots: instanceId={}, active={}, max={}"
                    , instanceId, activeCount, maxSlots);
            return;
        }

        for (ExecutionJob job : jobs) {
            if (remainingSlots <= 0) {
                break;
            }

            if (jobPort.existsByJobIdAndStatusIn(job.getJobId(), ACTIVE_STATUSES)) {
                log.debug("[Dispatch] Duplicate skip: jobId={}", job.getJobId());
                continue;
            }

            dispatchService.prepareForDispatch(job);
            jobPort.save(job);
            publishPort.publishExecuteCommand(job);
            remainingSlots--;

            log.info("[Dispatch] Job queued: jobExcnId={}, jobId={}, instanceId={}, remainingSlots={}"
                    , job.getJobExcnId(), job.getJobId(), instanceId, remainingSlots);
        }
    }
}
