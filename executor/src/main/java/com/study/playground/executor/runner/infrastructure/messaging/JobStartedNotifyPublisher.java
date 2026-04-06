package com.study.playground.executor.runner.infrastructure.messaging;

import com.study.playground.avro.executor.ExecutorJobStartedEvent;
import com.study.playground.executor.runner.domain.port.out.NotifyJobStartedPort;
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
public class JobStartedNotifyPublisher implements NotifyJobStartedPort {

    private final EventPublisher eventPublisher;

    @Override
    public void notify(String jobExcnId, String pipelineExcnId, String jobId, int buildNo) {
        var event = ExecutorJobStartedEvent.newBuilder()
                .setJobExcnId(jobExcnId)
                .setPipelineExcnId(pipelineExcnId)
                .setJobId(jobId)
                .setBuildNo(buildNo)
                .setIdempotencyKey(UUID.randomUUID().toString())
                .setTimestamp(Instant.now().toString())
                .build();

        eventPublisher.publish(
                "EXECUTION_JOB"
                , jobExcnId
                , "JOB_STARTED_NOTIFY"
                , event
                , Topics.EXECUTOR_NOTIFY_JOB_STARTED
                , jobExcnId
        );

        log.info("[Notify] Job started sent to op: jobExcnId={}, buildNo={}", jobExcnId, buildNo);
    }
}
