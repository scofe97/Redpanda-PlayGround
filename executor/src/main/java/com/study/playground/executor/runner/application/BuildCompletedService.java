package com.study.playground.executor.runner.application;

import com.study.playground.executor.dispatch.domain.model.ExecutionJob;
import com.study.playground.executor.dispatch.domain.model.ExecutionJobStatus;
import com.study.playground.executor.dispatch.domain.port.in.EvaluateDispatchUseCase;
import com.study.playground.executor.dispatch.domain.port.out.ExecutionJobPort;
import com.study.playground.executor.runner.domain.model.BuildCallback;
import com.study.playground.executor.runner.domain.port.in.HandleBuildCompletedUseCase;
import com.study.playground.executor.runner.domain.port.out.*;
import com.study.playground.executor.runner.domain.service.BuildLifecycleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * HandleBuildCompletedUseCase 구현.
 *
 * 흐름:
 *   1. Jenkins 경로에서 jobId 추출 → executor DB 매칭
 *   2. 로그 파일 적재 (실패해도 계속 진행)
 *   3. executor DB: RUNNING → SUCCESS/FAILURE/... + END_DT
 *   4. op DB: 상태 UPDATE (cross-schema)
 *   5. op에 완료 토픽 발행
 *   6. tryDispatch() (슬롯 반납)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BuildCompletedService implements HandleBuildCompletedUseCase {

    private final ResolveJobByBuildPort resolvePort;
    private final ExecutionJobPort jobPort;
    private final SaveBuildLogPort logPort;
    private final UpdateOperatorStatusPort operatorStatusPort;
    private final NotifyJobCompletedPort notifyPort;
    private final BuildLifecycleService lifecycleService;
    private final EvaluateDispatchUseCase dispatchUseCase;

    @Override
    @Transactional
    public void handle(BuildCallback callback) {
        var jobId = callback.extractJobId();

        ExecutionJob job = resolvePort.findByJobIdAndBuildNo(jobId, callback.buildNo())
                .orElse(null);

        if (job == null) {
            log.warn("[BuildCompleted] No matching job: jobId={}, buildNo={}"
                    , jobId, callback.buildNo());
            return;
        }

        if (job.getStatus().isTerminal()) {
            log.debug("[BuildCompleted] Already terminal: jobExcnId={}", job.getJobExcnId());
            return;
        }

        // 1. 로그 파일 적재 (실패 허용)
        String logFilePath = null;
        boolean logSaved = false;
        if (callback.logContent() != null && !callback.logContent().isBlank()) {
            var dirPath = callback.toLogDirectoryPath();
            logSaved = logPort.save(dirPath, job.getJobExcnId(), callback.logContent());
            if (logSaved) {
                logFilePath = dirPath + "/" + job.getJobExcnId() + "_0";
            }
        }

        // 2. executor DB 상태 전이
        lifecycleService.applyCompleted(job, callback);
        lifecycleService.recordLogResult(job, logSaved);
        jobPort.save(job);

        // 3. op DB cross-schema UPDATE
        var newStatus = ExecutionJobStatus.fromJenkinsResult(callback.result());
        operatorStatusPort.updateJobExecutionStatus(job.getJobExcnId(), newStatus.name());

        // 4. op에 완료 토픽 발행
        boolean success = newStatus == ExecutionJobStatus.SUCCESS;
        notifyPort.notify(
                job.getJobExcnId()
                , job.getPipelineExcnId()
                , success
                , callback.result()
                , logFilePath
                , logSaved ? "Y" : "N"
                , success ? null : callback.result()
        );

        log.info("[BuildCompleted] Job {}: jobExcnId={}, buildNo={}, logSaved={}"
                , newStatus, job.getJobExcnId(), callback.buildNo(), logSaved);

        // 5. 트리거 ③: 슬롯 반납 → 대기 Job 실행
        dispatchUseCase.tryDispatch();
    }
}
