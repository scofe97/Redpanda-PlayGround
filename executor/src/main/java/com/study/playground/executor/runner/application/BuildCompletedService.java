package com.study.playground.executor.runner.application;

import com.study.playground.executor.dispatch.domain.model.ExecutionJob;
import com.study.playground.executor.dispatch.domain.model.ExecutionJobStatus;
import com.study.playground.executor.dispatch.domain.port.in.EvaluateDispatchUseCase;
import com.study.playground.executor.dispatch.domain.port.out.ExecutionJobPort;
import com.study.playground.executor.dispatch.domain.service.DispatchService;
import com.study.playground.executor.runner.domain.model.BuildCallback;
import com.study.playground.executor.runner.domain.port.in.HandleBuildCompletedUseCase;
import com.study.playground.executor.runner.domain.port.out.NotifyJobCompletedPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class BuildCompletedService implements HandleBuildCompletedUseCase {

    private final ExecutionJobPort jobPort;
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

        // 1. executor DB 상태 전이
        dispatchService.markAsCompleted(job, callback.result());
        dispatchService.recordLogResult(job, false);
        jobPort.save(job);

        // 2. op에 완료 토픽 발행 (op가 자체 DB 갱신)
        var newStatus = ExecutionJobStatus.fromJenkinsResult(callback.result());
        boolean success = newStatus == ExecutionJobStatus.SUCCESS;
        notifyPort.notify(
                job.getJobExcnId()
                , job.getPipelineExcnId()
                , success
                , callback.result()
                , null
                , "N"
                , success ? null : callback.result()
        );

        log.info("[BuildCompleted] Job {}: jobExcnId={}, buildNumber={}"
                , newStatus, job.getJobExcnId(), callback.buildNumber());

        // 3. 트리거 ③: 슬롯 반납 → 대기 Job 실행
        dispatchUseCase.tryDispatch();
    }
}
