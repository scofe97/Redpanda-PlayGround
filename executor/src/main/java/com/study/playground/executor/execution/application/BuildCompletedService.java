package com.study.playground.executor.execution.application;

import com.study.playground.executor.execution.domain.model.ExecutionJob;
import com.study.playground.executor.execution.domain.model.ExecutionJobStatus;
import com.study.playground.executor.execution.domain.port.out.ExecutionJobPort;
import com.study.playground.executor.execution.domain.port.out.JobDefinitionQueryPort;
import com.study.playground.executor.execution.domain.service.DispatchService;
import com.study.playground.executor.execution.domain.model.BuildCallback;
import com.study.playground.executor.execution.domain.port.in.HandleBuildCompletedUseCase;
import com.study.playground.executor.execution.domain.port.out.NotifyJobCompletedPort;
import com.study.playground.executor.execution.domain.port.out.SaveBuildLogPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class BuildCompletedService implements HandleBuildCompletedUseCase {

    private final ExecutionJobPort jobPort;
    private final SaveBuildLogPort logPort;
    private final NotifyJobCompletedPort notifyPort;
    private final JobDefinitionQueryPort jobDefinitionQueryPort;
    private final DispatchService dispatchService;

    @Override
    @Transactional
    public void handle(BuildCallback callback) {
        ExecutionJob job = jobPort.findByJobIdAndBuildNo(
                callback.jobId(), callback.buildNumber())
                .orElse(null);

        if (job == null) {
            log.warn("[BuildCompleted] No matching job: jobId={}, buildNumber={}"
                    , callback.jobId(), callback.buildNumber());
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
            var defInfo = jobDefinitionQueryPort.load(job.getJobId()).orElse(null);
            if (defInfo == null) {
                log.warn("[BuildCompleted] Job definition not found, skipping log save: jobExcnId={}", job.getJobExcnId());
            } else {
                var dirPath = defInfo.jenkinsJobPath();
                logSaved = logPort.save(dirPath, job.getJobExcnId(), callback.logContent());
                if (logSaved) {
                    logFilePath = dirPath + "/" + job.getJobExcnId() + "_0";
                }
            }
        }

        // 2. executor DB 상태 전이
        dispatchService.markAsCompleted(job, callback.result());
        dispatchService.recordLogResult(job, logSaved);
        jobPort.save(job);

        // 3. op에 완료 토픽 발행 (op가 자체 DB 갱신)
        var newStatus = ExecutionJobStatus.fromJenkinsResult(callback.result());
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

        log.info("[BuildCompleted] Job {}: jobExcnId={}, buildNumber={}, logSaved={}"
                , newStatus, job.getJobExcnId(), callback.buildNumber(), logSaved);
    }
}
