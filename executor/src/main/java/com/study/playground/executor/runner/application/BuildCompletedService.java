package com.study.playground.executor.runner.application;

import com.study.playground.executor.dispatch.domain.model.ExecutionJob;
import com.study.playground.executor.dispatch.domain.model.ExecutionJobStatus;
import com.study.playground.executor.dispatch.domain.port.in.EvaluateDispatchUseCase;
import com.study.playground.executor.dispatch.domain.port.out.ExecutionJobPort;
import com.study.playground.executor.dispatch.domain.service.DispatchService;
import com.study.playground.executor.runner.domain.model.BuildCallback;
import com.study.playground.executor.runner.domain.port.in.HandleBuildCompletedUseCase;
import com.study.playground.executor.runner.domain.port.out.NotifyJobCompletedPort;
import com.study.playground.executor.runner.domain.port.out.SaveBuildLogPort;
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
    private final DispatchService dispatchService;
    private final EvaluateDispatchUseCase dispatchUseCase;

    @Override
    @Transactional
    public void handle(BuildCallback callback) {
        ExecutionJob job = jobPort.findById(callback.jobExcnId())
                .orElse(null);

        if (job == null) {
            log.warn("[BuildCompleted] No matching job: jobExcnId={}, buildNumber={}"
                    , callback.jobExcnId(), callback.buildNumber());
            return;
        }

        if (job.getStatus().isTerminal()) {
            log.debug("[BuildCompleted] Already terminal: jobExcnId={}", job.getJobExcnId());
            return;
        }

        if (job.getBuildNo() != null && job.getBuildNo() != callback.buildNumber()) {
            log.warn("[BuildCompleted] BuildNo mismatch: jobExcnId={}, expected={}, actual={}"
                    , job.getJobExcnId(), job.getBuildNo(), callback.buildNumber());
            return;
        }

        // 1. 로그 파일 적재 (실패 허용)
        String logFilePath = null;
        boolean logSaved = false;
        if (callback.logContent() != null && !callback.logContent().isBlank()) {
            var dirPath = job.getJobName() != null ? job.getJobName() : job.getJobExcnId();
            logSaved = logPort.save(dirPath, job.getJobExcnId(), callback.logContent());
            if (logSaved) {
                logFilePath = dirPath + "/" + job.getJobExcnId() + "_0";
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

        // 3. 트리거 ③: 슬롯 반납 → 대기 Job 실행
        dispatchUseCase.tryDispatch();
    }
}
