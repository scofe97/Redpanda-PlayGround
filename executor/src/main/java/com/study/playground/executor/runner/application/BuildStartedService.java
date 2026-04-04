package com.study.playground.executor.runner.application;

import com.study.playground.executor.dispatch.domain.model.ExecutionJob;
import com.study.playground.executor.dispatch.domain.port.in.EvaluateDispatchUseCase;
import com.study.playground.executor.dispatch.domain.port.out.ExecutionJobPort;
import com.study.playground.executor.runner.domain.model.BuildCallback;
import com.study.playground.executor.runner.domain.port.in.HandleBuildStartedUseCase;
import com.study.playground.executor.runner.domain.port.out.ResolveJobByBuildPort;
import com.study.playground.executor.runner.domain.port.out.UpdateOperatorStatusPort;
import com.study.playground.executor.runner.domain.service.BuildLifecycleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * HandleBuildStartedUseCase 구현.
 *
 * 흐름:
 *   1. Jenkins 경로에서 jobId 추출 → executor DB 매칭
 *   2. executor DB: QUEUED → RUNNING + BGNG_DT
 *   3. op DB: PENDING → RUNNING (cross-schema UPDATE)
 *   4. tryDispatch() (슬롯 변동 반영)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BuildStartedService implements HandleBuildStartedUseCase {

    private final ResolveJobByBuildPort resolvePort;
    private final ExecutionJobPort jobPort;
    private final UpdateOperatorStatusPort operatorStatusPort;
    private final BuildLifecycleService lifecycleService;
    private final EvaluateDispatchUseCase dispatchUseCase;

    @Override
    @Transactional
    public void handle(BuildCallback callback) {
        var jobId = callback.extractJobId();

        ExecutionJob job = resolvePort.findByJobIdAndBuildNo(jobId, callback.buildNo())
                .orElse(null);

        if (job == null) {
            log.warn("[BuildStarted] No matching job: jobId={}, buildNo={}"
                    , jobId, callback.buildNo());
            return;
        }

        if (job.getStatus().isTerminal()) {
            log.debug("[BuildStarted] Already terminal: jobExcnId={}", job.getJobExcnId());
            return;
        }

        // executor DB 상태 전이
        lifecycleService.applyStarted(job, callback);
        jobPort.save(job);

        // op DB cross-schema UPDATE
        operatorStatusPort.updateJobExecutionStatus(job.getJobExcnId(), "RUNNING");

        log.info("[BuildStarted] Job RUNNING: jobExcnId={}, buildNo={}, jenkinsPath={}"
                , job.getJobExcnId(), callback.buildNo(), callback.jenkinsPath());

        // 트리거 ②: 슬롯 변동 반영
        dispatchUseCase.tryDispatch();
    }
}
