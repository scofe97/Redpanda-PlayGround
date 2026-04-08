package com.study.playground.executor.execution.infrastructure.messaging;

import com.study.playground.avro.executor.ExecutorJobCompletedEvent;
import com.study.playground.executor.execution.domain.port.out.NotifyJobCompletedPort;
import com.study.playground.kafka.outbox.EventPublisher;
import com.study.playground.kafka.topic.Topics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class JobCompletedNotifyPublisher implements NotifyJobCompletedPort {

    private final EventPublisher eventPublisher;

    @Override
    public void notify(
            String jobExcnId
            , String pipelineExcnId
            , boolean success
            , String result
            , String logFilePath
            , String logFileYn
            , String errorMessage
    ) {
        var event = ExecutorJobCompletedEvent.newBuilder()
                .setJobExcnId(jobExcnId)
                .setPipelineExcnId(pipelineExcnId)
                .setSuccess(success)
                .setResult(result != null ? result : "")
                .setLogFilePath(logFilePath)
                .setLogFileYn(logFileYn)
                .setErrorMessage(errorMessage)
                .setIdempotencyKey(UUID.randomUUID().toString())
                .setTimestamp(Instant.now().toString())
                .build();

        eventPublisher.publish(
                "EXECUTION_JOB"
                , jobExcnId
                , "JOB_COMPLETED_NOTIFY"
                , event
                , Topics.EXECUTOR_NOTIFY_JOB_COMPLETED
                , jobExcnId
        );

        log.info("[Notify] Job completed sent to op: jobExcnId={}, result={}", jobExcnId, result);
    }
}
