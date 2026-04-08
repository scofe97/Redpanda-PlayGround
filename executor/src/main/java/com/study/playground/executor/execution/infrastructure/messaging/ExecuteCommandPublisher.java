package com.study.playground.executor.execution.infrastructure.messaging;

import com.study.playground.avro.executor.ExecutorJobExecuteCommand;
import com.study.playground.executor.execution.domain.model.ExecutionJob;
import com.study.playground.executor.execution.domain.port.out.PublishExecuteCommandPort;
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
public class ExecuteCommandPublisher implements PublishExecuteCommandPort {

    private final EventPublisher eventPublisher;

    @Override
    public void publishExecuteCommand(ExecutionJob job) {
        var cmd = ExecutorJobExecuteCommand.newBuilder()
                .setJobExcnId(job.getJobExcnId())
                .setJobId(job.getJobId())
                .setIdempotencyKey(UUID.randomUUID().toString())
                .setTimestamp(Instant.now().toString())
                .build();

        eventPublisher.publish(
                "EXECUTION_JOB"
                , job.getJobExcnId()
                , "JOB_EXECUTE"
                , cmd
                , Topics.EXECUTOR_CMD_JOB_EXECUTE
                , job.getJobExcnId()
        );
    }
}
