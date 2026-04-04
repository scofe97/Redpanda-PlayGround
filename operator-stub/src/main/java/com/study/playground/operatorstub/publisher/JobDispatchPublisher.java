package com.study.playground.operatorstub.publisher;

import com.study.playground.avro.executor.ExecutorJobDispatchCommand;
import com.study.playground.kafka.outbox.EventPublisher;
import com.study.playground.kafka.topic.Topics;
import com.study.playground.operatorstub.domain.OperatorJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class JobDispatchPublisher {

    private final EventPublisher eventPublisher;

    public void publishJobDispatch(OperatorJob operatorJob) {
        String idempotencyKey = UUID.randomUUID().toString();

        ExecutorJobDispatchCommand cmd = ExecutorJobDispatchCommand.newBuilder()
                .setExecutionJobId(operatorJob.getId())
                .setExecutionPipelineId(operatorJob.getExecutionPipelineId())
                .setJobId(operatorJob.getJobId())
                .setPipelineId(operatorJob.getPipelineId())
                .setJobName(operatorJob.getJobName())
                .setJobOrder(operatorJob.getJobOrder())
                .setJenkinsInstanceId(operatorJob.getJenkinsInstanceId())
                .setConfigJson(operatorJob.getConfigJson())
                .setIdempotencyKey(idempotencyKey)
                .setTimestamp(Instant.now().toString())
                .build();

        eventPublisher.publish(
                "OPERATOR_JOB"
                , String.valueOf(operatorJob.getId())
                , "JOB_DISPATCH"
                , cmd
                , Topics.EXECUTOR_CMD_JOB_DISPATCH
                , String.valueOf(operatorJob.getId())
        );

        log.info("[OpPublisher] Dispatched job: id={}, jobName={}, pipeline={}"
                , operatorJob.getId(), operatorJob.getJobName()
                , operatorJob.getExecutionPipelineId());
    }
}
