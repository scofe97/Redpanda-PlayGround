package com.study.playground.executor.runner.application;

import com.study.playground.executor.dispatch.domain.model.ExecutionJob;
import com.study.playground.executor.dispatch.domain.port.in.EvaluateDispatchUseCase;
import com.study.playground.executor.dispatch.domain.port.out.ExecutionJobPort;
import com.study.playground.executor.dispatch.domain.service.DispatchService;
import com.study.playground.executor.runner.domain.model.BuildCallback;
import com.study.playground.executor.runner.domain.port.in.HandleBuildStartedUseCase;
import com.study.playground.executor.runner.domain.port.out.NotifyJobStartedPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class BuildStartedService implements HandleBuildStartedUseCase {

    private final ExecutionJobPort jobPort;
    private final NotifyJobStartedPort notifyStartedPort;
    private final DispatchService dispatchService;
    private final EvaluateDispatchUseCase dispatchUseCase;

    @Override
    @Transactional
    public void handle(BuildCallback callback) {
        ExecutionJob job = jobPort.findById(callback.jobExcnId())
                .orElse(null);

        if (job == null) {
            log.warn("[BuildStarted] No matching job: jobExcnId={}, buildNumber={}"
                    , callback.jobExcnId(), callback.buildNumber());
            return;
        }

        if (job.getStatus().isTerminal()) {
            log.debug("[BuildStarted] Already terminal: jobExcnId={}", job.getJobExcnId());
            return;
        }

        // 1. executor DB 상태 전이
        dispatchService.markAsRunning(job, callback.buildNumber());
        jobPort.save(job);

        // 2. op에 시작 토픽 발행 (op가 자체 DB 갱신)
        notifyStartedPort.notify(
                job.getJobExcnId()
                , job.getPipelineExcnId()
                , job.getJobId()
                , callback.buildNumber()
        );

        log.info("[BuildStarted] Job RUNNING: jobExcnId={}, buildNumber={}"
                , job.getJobExcnId(), callback.buildNumber());

        // 3. 트리거 ②: 슬롯 변동 반영
        dispatchUseCase.tryDispatch();
    }
}
