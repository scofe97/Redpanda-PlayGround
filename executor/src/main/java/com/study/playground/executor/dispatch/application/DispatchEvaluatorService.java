package com.study.playground.executor.dispatch.application;

import com.study.playground.executor.config.ExecutorProperties;
import com.study.playground.executor.dispatch.domain.model.ExecutionJob;
import com.study.playground.executor.dispatch.domain.model.ExecutionJobStatus;
import com.study.playground.executor.dispatch.domain.port.in.EvaluateDispatchUseCase;
import com.study.playground.executor.dispatch.domain.port.out.ExecutionJobPort;
import com.study.playground.executor.dispatch.domain.port.out.JenkinsQueryPort;
import com.study.playground.executor.dispatch.domain.port.out.JobDefinitionQueryPort;
import com.study.playground.executor.dispatch.domain.port.out.PublishExecuteCommandPort;
import com.study.playground.executor.dispatch.domain.service.DispatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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

        for (ExecutionJob job : pendingJobs) {
            try {
                dispatch(job);
            } catch (Exception e) {
                log.error("[Dispatch] Failed for job={}: {}"
                        , job.getJobExcnId(), e.getMessage(), e);
            }
        }
    }

    private void dispatch(ExecutionJob job) {
        // 1. 동일 jobId(정의)가 이미 QUEUED/RUNNING이면 중복 실행 방지
        if (jobPort.existsByJobIdAndStatusIn(job.getJobId(), ACTIVE_STATUSES)) {
            log.debug("[Dispatch] Duplicate skip: jobId={} already QUEUED/RUNNING"
                    , job.getJobId());
            return;
        }

        // 2. JobDefinition에서 Jenkins 인스턴스 ID 조회
        var defInfo = jobDefinitionQueryPort.load(job.getJobId());
        long jenkinsInstanceId = defInfo.jenkinsInstanceId();

        // 3. 해당 Jenkins에 슬롯이 있는지 확인
        if (!jenkinsQueryPort.isImmediatelyExecutable(jenkinsInstanceId)) {
            log.debug("[Dispatch] No slot: jobExcnId={}, jenkinsInstance={}"
                    , job.getJobExcnId(), jenkinsInstanceId);
            return;
        }

        // 4. QUEUED 전환 (buildNo는 SUBMITTED 전환 시 기록)
        dispatchService.prepareForDispatch(job);
        jobPort.save(job);

        // 5. 실행 토픽 발행
        publishPort.publishExecuteCommand(job);

        log.info("[Dispatch] Job queued: jobExcnId={}, jobId={}, priority={}"
                , job.getJobExcnId(), job.getJobId(), job.getPriority());
    }
}
