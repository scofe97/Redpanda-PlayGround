package com.study.playground.executor.execution.application;

import com.study.playground.executor.execution.domain.model.ExecutionJob;
import com.study.playground.executor.execution.domain.port.in.ReceiveJobUseCase;
import com.study.playground.executor.execution.domain.port.out.ExecutionJobPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * ReceiveJobUseCase 구현.
 * 도메인 서비스 + out-port를 조합하여 Job 수신 유스케이스를 완성한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReceiveJobService implements ReceiveJobUseCase {

    private static final int DEFAULT_PRIORITY = 1;

    private final ExecutionJobPort jobPort;

    @Override
    @Transactional
    public void receive(
            String jobExcnId
            , String pipelineExcnId
            , String jobId
            , LocalDateTime priorityDt
            , String rgtrId
    ) {
        if (jobPort.existsById(jobExcnId)) {
            log.debug("[Receive] Duplicate job ignored: jobExcnId={}", jobExcnId);
            return;
        }

        ExecutionJob job = ExecutionJob.create(
                jobExcnId, pipelineExcnId, jobId
                , DEFAULT_PRIORITY, priorityDt, rgtrId
        );

        jobPort.save(job);
        log.info("[Receive] Job received: jobExcnId={}, jobId={}, priority={}, priorityDt={}"
                , jobExcnId, jobId, DEFAULT_PRIORITY, priorityDt);
    }
}
