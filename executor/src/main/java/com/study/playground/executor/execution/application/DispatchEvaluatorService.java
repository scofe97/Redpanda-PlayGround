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

    /**
     * PENDING 작업을 Jenkins 인스턴스별로 묶은 뒤, health/slot 조건을 만족하는 건만
     * QUEUED로 승격시키고 execute command를 발행한다.
     */
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
            var defInfoOpt = jobDefinitionQueryPort.load(job.getJobId());
            if (defInfoOpt.isEmpty()) {
                log.error("[Dispatch] Job definition not found, marking FAILURE: jobExcnId={}, jobId={}"
                        , job.getJobExcnId(), job.getJobId());
                job.transitionTo(ExecutionJobStatus.FAILURE);
                jobPort.save(job);
                continue;
            }
            jobsByInstance.computeIfAbsent(defInfoOpt.get().jenkinsInstanceId(), ignored -> new ArrayList<>())
                    .add(job);
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

    private void dispatchForInstance(long instanceId, List<ExecutionJob> jobs) {
        if (!jenkinsQueryPort.isHealthy(instanceId)) {
            log.warn("[Dispatch] Jenkins unhealthy, skipping: instanceId={}", instanceId);
            return;
        }

        // 슬롯 계산은 executor DB의 활성 작업 수와 Jenkins 실시간 executor 정보를 함께 본다.
        int activeCount = jobPort.countActiveJobsByJenkinsInstanceId(instanceId, ACTIVE_STATUSES);
        int dispatchCapacity = jenkinsQueryPort.isImmediatelyExecutable(instanceId);
        int remainingSlots = dispatchCapacity - activeCount;

        if (remainingSlots <= 0) {
            log.debug("[Dispatch] No slots: instanceId={}, active={}, capacity={}"
                    , instanceId, activeCount, dispatchCapacity);
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

            // execute consumer가 실제 Jenkins 트리거를 담당하므로 여기서는 QUEUED + command 발행까지만 처리한다.
            dispatchService.prepareForDispatch(job);
            jobPort.save(job);
            publishPort.publishExecuteCommand(job);
            remainingSlots--;

            log.info("[Dispatch] Job queued: jobExcnId={}, jobId={}, instanceId={}, remainingSlots={}"
                    , job.getJobExcnId(), job.getJobId(), instanceId, remainingSlots);
        }
    }
}
